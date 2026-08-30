package com.github.llmmock.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.llmmock.LlmMockApplication;
import com.github.llmmock.config.CachedBodyRequest;
import com.github.llmmock.support.AppInstances;
import com.github.llmmock.support.SigV4Verifier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers SigV4 re-signing on the Bedrock proxy path.
 *
 * <p>The upstream standing in for AWS is, as everywhere else here, another instance of this
 * application - plus one test-only filter that captures exactly what arrived. The captured
 * request is then checked with {@link SigV4Verifier}, which recomputes the signature the
 * way a real AWS endpoint would. A signature that validates there is one that covers the
 * upstream's host, path, query and body.
 */
class SigV4ProxyTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String CLAUDE = "anthropic.claude-sonnet-4-5-20250929-v1:0";

    @TempDir
    Path recordingsDir;

    private final AppInstances instances = new AppInstances();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearCapture() {
        CaptureConfiguration.LAST.set(null);
    }

    @AfterEach
    void stopAll() {
        instances.close();
    }

    // --- the capturing upstream --------------------------------------------------------

    /** What the upstream received, kept verbatim so the signature can be checked against it. */
    record Captured(String method, String path, String query, Map<String, List<String>> headers,
                    byte[] body) {
    }

    /**
     * A test-only filter added to the upstream instance. The mock does not record request
     * headers by design, and teaching it to would mean writing credentials into its request
     * log, so the observation lives here instead.
     */
    @Configuration
    static class CaptureConfiguration {

        static final AtomicReference<Captured> LAST = new AtomicReference<>();

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 15)
        OncePerRequestFilter capturingFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request,
                                                HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {
                    if (!request.getRequestURI().startsWith("/__admin")) {
                        Map<String, List<String>> headers = new LinkedHashMap<>();
                        var names = request.getHeaderNames();
                        while (names.hasMoreElements()) {
                            String name = names.nextElement();
                            List<String> values = new ArrayList<>();
                            var enumeration = request.getHeaders(name);
                            while (enumeration.hasMoreElements()) {
                                values.add(enumeration.nextElement());
                            }
                            headers.put(name, values);
                        }
                        CachedBodyRequest cached = CachedBodyRequest.find(request);
                        LAST.set(new Captured(request.getMethod(), request.getRequestURI(),
                                request.getQueryString(), headers,
                                cached == null ? new byte[0] : cached.body()));
                    }
                    chain.doFilter(request, response);
                }
            };
        }
    }

    // --- instance management -----------------------------------------------------------

    private String urlOf(ConfigurableApplicationContext context) {
        return AppInstances.urlOf(context);
    }

    private ConfigurableApplicationContext startUpstream() {
        return instances.start(new Class<?>[] {LlmMockApplication.class, CaptureConfiguration.class},
                Map.of("llm-mock.default-response-template", "[upstream] answered: {{prompt}}"));
    }

    private ConfigurableApplicationContext startProxy(String upstreamUrl, boolean signing) {
        Map<String, String> properties = new LinkedHashMap<>(Map.of(
                "llm-mock.mode", "PROXY",
                "llm-mock.proxy.recordings-dir", recordingsDir.toString(),
                "llm-mock.proxy.targets.bedrock", upstreamUrl + "/bedrock"));
        if (signing) {
            properties.putAll(Map.of(
                    "llm-mock.proxy.sigv4.bedrock.enabled", "true",
                    // The stand-in upstream is on localhost, so the region cannot be read
                    // out of the hostname the way it would be for a real AWS endpoint.
                    "llm-mock.proxy.sigv4.bedrock.region", "us-east-1",
                    "llm-mock.proxy.sigv4.bedrock.access-key-id", ACCESS_KEY,
                    "llm-mock.proxy.sigv4.bedrock.secret-access-key", SECRET_KEY));
        }
        return instances.start(properties);
    }

    private Captured captured() {
        Captured captured = CaptureConfiguration.LAST.get();
        assertThat(captured).as("the upstream received a request").isNotNull();
        return captured;
    }

    private boolean signatureIsValid(Captured captured) {
        return SigV4Verifier.verify(captured.method(), captured.path(), captured.query(),
                captured.headers(), captured.body(), SECRET_KEY);
    }

    private String header(Captured captured, String name) {
        return captured.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> String.join(",", entry.getValue()))
                .findFirst().orElse(null);
    }

    private HttpResponse<String> post(String url, String body, String... headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private static final String CONVERSE_BODY = """
            {"messages":[{"role":"user","content":[{"text":"Hello"}]}]}""";

    private String conversePath() {
        return "/bedrock/model/" + CLAUDE + "/converse";
    }

    // --- tests -------------------------------------------------------------------------

    @Test
    void theProxyPresentsASignatureThatValidatesForTheUpstream() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream), true);

        HttpResponse<String> response =
                post(urlOf(proxy) + conversePath(), CONVERSE_BODY);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("[upstream] answered: Hello");

        Captured captured = captured();
        assertThat(header(captured, "Authorization")).startsWith("AWS4-HMAC-SHA256 ");
        assertThat(signatureIsValid(captured)).isTrue();
    }

    @Test
    void theSignatureIsBoundToTheUpstreamHostNotTheMocks() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream), true);

        post(urlOf(proxy) + conversePath(), CONVERSE_BODY);

        Captured captured = captured();
        String upstreamHost = URI.create(urlOf(upstream)).getAuthority();
        assertThat(header(captured, "Host")).isEqualTo(upstreamHost);
        // host is in SignedHeaders and the signature validates, so it really was signed
        // for this host - which is the entire reason re-signing is necessary.
        assertThat(SigV4Verifier.parse(header(captured, "Authorization")).signedHeaders())
                .contains("host");
        assertThat(signatureIsValid(captured)).isTrue();
    }

    @Test
    void theCallersOwnSignatureIsReplacedRatherThanForwarded() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream), true);

        post(urlOf(proxy) + conversePath(), CONVERSE_BODY,
                "Authorization", "AWS4-HMAC-SHA256 Credential=AKIADUMMY/20200101/us-east-1/"
                        + "bedrock/aws4_request, SignedHeaders=host, Signature=deadbeef",
                "X-Amz-Date", "20200101T000000Z",
                "X-Amz-Content-Sha256", "0000000000000000000000000000000000000000000000000000000000000000");

        Captured captured = captured();
        assertThat(header(captured, "Authorization")).doesNotContain("AKIADUMMY")
                .contains(ACCESS_KEY);
        assertThat(header(captured, "X-Amz-Date")).isNotEqualTo("20200101T000000Z");
        assertThat(signatureIsValid(captured)).isTrue();
    }

    @Test
    void aModelIdContainingAColonSurvivesTheRoundTrip() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream), true);

        HttpResponse<String> response = post(urlOf(proxy) + conversePath(), CONVERSE_BODY);

        assertThat(response.statusCode()).isEqualTo(200);
        Captured captured = captured();
        // The path reached the upstream intact, and the signature covers it.
        assertThat(captured.path()).contains(CLAUDE);
        assertThat(signatureIsValid(captured)).isTrue();
    }

    @Test
    void theSignatureCoversTheRequestBody() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream), true);

        post(urlOf(proxy) + conversePath(), CONVERSE_BODY);

        Captured captured = captured();
        assertThat(new String(captured.body(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(CONVERSE_BODY);
        assertThat(signatureIsValid(captured)).isTrue();
        // Verifying the same signature against a different body must fail, or the check
        // above would prove nothing.
        assertThat(SigV4Verifier.verify(captured.method(), captured.path(), captured.query(),
                captured.headers(), "{\"other\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                SECRET_KEY)).isFalse();
    }

    @Test
    void queryParametersAreCoveredForStreamingEndpoints() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream), true);

        HttpResponse<String> response = post(urlOf(proxy) + "/bedrock/model/" + CLAUDE
                + "/converse-stream?trace=ENABLED", CONVERSE_BODY);

        assertThat(response.statusCode()).isEqualTo(200);
        Captured captured = captured();
        assertThat(captured.query()).isEqualTo("trace=ENABLED");
        assertThat(signatureIsValid(captured)).isTrue();
    }

    @Test
    void withSigningOffTheCallersHeaderIsForwardedUnchanged() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream), false);

        post(urlOf(proxy) + conversePath(), CONVERSE_BODY,
                "Authorization", "AWS4-HMAC-SHA256 Credential=AKIADUMMY/x, SignedHeaders=host, "
                        + "Signature=deadbeef");

        // Signing is opt-in: a proxy pointed at another mock has nothing to sign for.
        assertThat(header(captured(), "Authorization")).contains("AKIADUMMY");
    }

    @Test
    void theRealAwsSdkSignsForTheMockAndTheProxyResignsForTheUpstream() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream), true);

        try (BedrockRuntimeClient client = BedrockRuntimeClient.builder()
                .endpointOverride(URI.create(urlOf(proxy) + "/bedrock"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        // The application under test holds a dummy key; the real one lives
                        // in the proxy's configuration and never leaves it.
                        AwsBasicCredentials.create("AKIADUMMYCLIENTKEY", "dummy-client-secret")))
                .httpClient(UrlConnectionHttpClient.create())
                .build()) {

            ConverseResponse response = client.converse(request -> request
                    .modelId(CLAUDE)
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText("Hello")).build()));

            assertThat(response.output().message().content().get(0).text())
                    .isEqualTo("[upstream] answered: Hello");
        }

        Captured captured = captured();
        // The SDK's own signature was made with the dummy key for the proxy's host; what
        // reached the upstream is a fresh one made with the configured credentials.
        assertThat(header(captured, "Authorization"))
                .doesNotContain("AKIADUMMYCLIENTKEY")
                .contains(ACCESS_KEY);
        assertThat(signatureIsValid(captured)).isTrue();
    }
}
