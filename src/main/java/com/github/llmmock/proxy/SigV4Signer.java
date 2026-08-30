package com.github.llmmock.proxy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.llmmock.config.LlmMockProperties;
import com.github.llmmock.core.Provider;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/**
 * Re-signs a proxied request with AWS SigV4.
 *
 * <p>A SigV4 signature covers the {@code Host} header, the path, the query and the body.
 * The caller signed for this mock's host, so that signature cannot be forwarded: the
 * request has to be signed again for the real endpoint, and usually with different
 * credentials, since the application under test is normally configured with a dummy key.
 *
 * <p>The signing itself is delegated to the AWS SDK. Canonicalisation is the part of SigV4
 * that hand-rolled implementations get wrong - a Bedrock model id such as
 * {@code anthropic.claude-sonnet-4-5-20250929-v1:0} sits in the URL path, and the canonical
 * URI is encoded once more on top of the encoding already on the wire.
 */
@Component
public class SigV4Signer {

    private static final Logger log = LoggerFactory.getLogger(SigV4Signer.class);

    /** Region-shaped host label, e.g. {@code us-east-1} or {@code ap-northeast-1}. */
    private static final Pattern REGION_LABEL = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d+$");

    /**
     * Headers carrying the caller's own signature. They describe a signature over a
     * different host and must never survive into the signed request.
     */
    private static final Set<String> STALE_SIGNING_HEADERS = Set.of("authorization",
            "x-amz-date", "x-amz-content-sha256", "x-amz-security-token", "x-amz-algorithm",
            "x-amz-credential", "x-amz-signature", "x-amz-signedheaders", "x-amz-expires");

    private final LlmMockProperties properties;
    private volatile DefaultCredentialsProvider defaultCredentials;
    private volatile String defaultRegion;

    public SigV4Signer(LlmMockProperties properties) {
        this.properties = properties;
    }

    /**
     * The request as it should go on the wire. The URI comes back from the signer rather
     * than from the caller, so the bytes sent can never disagree with what was signed.
     */
    public record Signed(URI uri, Map<String, List<String>> headers) {
    }

    public boolean isEnabledFor(Provider provider) {
        LlmMockProperties.SigV4 config = properties.getProxy().getSigv4().get(provider);
        return config != null && config.isEnabled();
    }

    /** True for headers the caller signed, which must be dropped before re-signing. */
    public static boolean isStaleSigningHeader(String header) {
        return header != null && STALE_SIGNING_HEADERS.contains(header.toLowerCase(Locale.ROOT));
    }

    public Signed sign(Provider provider, String method, URI uri,
                       Map<String, List<String>> headers, byte[] body) {
        LlmMockProperties.SigV4 config = properties.getProxy().getSigv4().get(provider);
        if (config == null || !config.isEnabled()) {
            throw new IllegalStateException("SigV4 is not enabled for " + provider);
        }
        String region = resolveRegion(config, uri);
        String service = resolveService(config, provider);

        Map<String, List<String>> toSign = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            // Host is taken from the URI, and Content-Length is added by the HTTP client
            // after signing, so neither is passed in here.
            if (!isStaleSigningHeader(name) && !"host".equalsIgnoreCase(name)
                    && !"content-length".equalsIgnoreCase(name)) {
                toSign.put(name, values);
            }
        });

        SdkHttpRequest request = SdkHttpRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .protocol(uri.getScheme())
                .host(uri.getHost())
                .port(uri.getPort() == -1 ? null : uri.getPort())
                .encodedPath(uri.getRawPath() == null || uri.getRawPath().isEmpty()
                        ? "/" : uri.getRawPath())
                .rawQueryParameters(queryParameters(uri.getRawQuery()))
                .headers(toSign)
                .build();

        AwsCredentialsIdentity credentials = resolveCredentials(config);
        byte[] payload = body == null ? new byte[0] : body;

        SignedRequest signed = AwsV4HttpSigner.create().sign(builder -> builder
                .identity(credentials)
                .request(request)
                .payload(ContentStreamProvider.fromByteArray(payload))
                .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region));

        return new Signed(signed.request().getUri(), signed.request().headers());
    }

    // --- resolution -------------------------------------------------------------------

    private AwsCredentialsIdentity resolveCredentials(LlmMockProperties.SigV4 config) {
        if (notBlank(config.getAccessKeyId()) && notBlank(config.getSecretAccessKey())) {
            return notBlank(config.getSessionToken())
                    ? AwsSessionCredentials.create(config.getAccessKeyId(),
                            config.getSecretAccessKey(), config.getSessionToken())
                    : AwsBasicCredentials.create(config.getAccessKeyId(),
                            config.getSecretAccessKey());
        }
        // Nothing configured: fall back to the standard chain, so environment variables, a
        // shared profile or an instance role all work with no extra configuration.
        if (defaultCredentials == null) {
            synchronized (this) {
                if (defaultCredentials == null) {
                    defaultCredentials = DefaultCredentialsProvider.create();
                }
            }
        }
        try {
            return defaultCredentials.resolveCredentials();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("SigV4 is enabled but no AWS credentials were found. "
                    + "Set llm-mock.proxy.sigv4.<provider>.access-key-id and .secret-access-key, "
                    + "or make credentials available to the default AWS provider chain.", ex);
        }
    }

    private String resolveRegion(LlmMockProperties.SigV4 config, URI uri) {
        if (notBlank(config.getRegion())) {
            return config.getRegion();
        }
        String fromHost = regionFromHost(uri.getHost());
        if (fromHost != null) {
            return fromHost;
        }
        if (defaultRegion == null) {
            synchronized (this) {
                if (defaultRegion == null) {
                    try {
                        defaultRegion = DefaultAwsRegionProviderChain.builder().build()
                                .getRegion().id();
                    } catch (RuntimeException ex) {
                        throw new IllegalStateException("SigV4 is enabled but no region could be "
                                + "determined. Set llm-mock.proxy.sigv4.<provider>.region.", ex);
                    }
                }
            }
        }
        return defaultRegion;
    }

    /** Picks the region label out of a host such as {@code bedrock-runtime.us-east-1.amazonaws.com}. */
    static String regionFromHost(String host) {
        if (host == null) {
            return null;
        }
        for (String label : host.split("\\.")) {
            if (REGION_LABEL.matcher(label).matches()) {
                return label;
            }
        }
        return null;
    }

    private String resolveService(LlmMockProperties.SigV4 config, Provider provider) {
        if (notBlank(config.getService())) {
            return config.getService();
        }
        if (provider == Provider.BEDROCK) {
            // Bedrock signs as "bedrock" even though the host is bedrock-runtime.*.
            return "bedrock";
        }
        throw new IllegalStateException("SigV4 is enabled for " + provider
                + " but no signing service name is configured. Set "
                + "llm-mock.proxy.sigv4." + provider.name().toLowerCase(Locale.ROOT) + ".service.");
    }

    /** The SDK re-encodes these, so names and values are handed over decoded. */
    private static Map<String, List<String>> queryParameters(String rawQuery) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parameters.computeIfAbsent(decode(name), key -> new ArrayList<>()).add(decode(value));
        }
        return parameters;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            log.debug("Could not decode query component '{}', using it verbatim", value);
            return value;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
