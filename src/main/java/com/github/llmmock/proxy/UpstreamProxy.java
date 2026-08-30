package com.github.llmmock.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.github.llmmock.config.LlmMockProperties;
import com.github.llmmock.core.Provider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Forwards one request to a real upstream API and streams the answer straight back. */
@Component
public class UpstreamProxy {

    /**
     * Headers that describe this hop rather than the message. Forwarding them corrupts the
     * exchange, and the JDK client rejects several of them outright.
     */
    private static final Set<String> HOP_BY_HOP = Set.of("host", "connection", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding",
            "upgrade", "content-length", "expect", "http2-settings");

    /**
     * Headers the JDK client sets itself and refuses to have set by hand. They are dropped
     * when applying a signed header set; the values the client computes match what was
     * signed, because both derive from the same URI and body.
     */
    private static final Set<String> CLIENT_MANAGED = Set.of("host", "content-length",
            "connection", "expect", "upgrade");

    private final LlmMockProperties properties;
    private final SigV4Signer sigV4Signer;
    private final HttpClient httpClient;

    public UpstreamProxy(LlmMockProperties properties, SigV4Signer sigV4Signer) {
        this.properties = properties;
        this.sigV4Signer = sigV4Signer;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getProxy().getConnectTimeout())
                // Redirects are returned to the caller so a recording reflects the exchange
                // the application under test will actually see.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The outcome of a forwarded call: what came back, and the bytes as seen by the client. */
    public record Result(int status, Map<String, List<String>> headers, byte[] body) {
    }

    public boolean hasTarget(Provider provider) {
        String target = properties.getProxy().getTargets().get(provider);
        return target != null && !target.isBlank();
    }

    /**
     * Sends {@code path} (already stripped of the provider prefix) upstream and copies the
     * response into {@code response} as it arrives, so streaming stays incremental.
     */
    public Result forward(Provider provider, HttpServletRequest request, String path, byte[] body,
                          HttpServletResponse response) throws IOException, InterruptedException {
        String target = properties.getProxy().getTargets().get(provider);
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("No proxy target configured for " + provider);
        }
        URI uri = URI.create(trimTrailingSlash(target) + path
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));

        Map<String, List<String>> headers = collectRequestHeaders(provider, request);

        if (sigV4Signer.isEnabledFor(provider)) {
            // Take both the URI and the headers from the signer: sending anything else
            // would mean putting bytes on the wire that the signature does not cover.
            SigV4Signer.Signed signed = sigV4Signer.sign(provider, request.getMethod(), uri,
                    headers, body);
            uri = signed.uri();
            headers = signed.headers();
        }

        HttpRequest.Builder upstream = HttpRequest.newBuilder(uri)
                .timeout(properties.getProxy().getRequestTimeout())
                .method(request.getMethod(), body == null || body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, values) -> {
            if (CLIENT_MANAGED.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            values.forEach(value -> upstream.header(name, value));
        });

        HttpResponse<InputStream> upstreamResponse =
                httpClient.send(upstream.build(), HttpResponse.BodyHandlers.ofInputStream());

        Map<String, List<String>> responseHeaders = copyResponseHeaders(upstreamResponse, response);
        response.setStatus(upstreamResponse.statusCode());

        byte[] captured = copyBody(upstreamResponse.body(), response.getOutputStream());
        return new Result(upstreamResponse.statusCode(), responseHeaders, captured);
    }

    /** The headers to forward: the caller's, minus this hop's, plus any configured overrides. */
    private Map<String, List<String>> collectRequestHeaders(Provider provider,
                                                            HttpServletRequest request) {
        Map<String, String> overrides = properties.getProxy().getHeaders()
                .getOrDefault(provider, Map.of());
        Set<String> overridden = overrides.keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        boolean signing = sigV4Signer.isEnabledFor(provider);

        Map<String, List<String>> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || overridden.contains(lower)) {
                continue;
            }
            // The X-Mock-* family controls this server; upstream has no use for it.
            if (lower.startsWith("x-mock-")) {
                continue;
            }
            // Ask for an unencoded body so recordings stay readable and reviewable.
            if (lower.equals("accept-encoding")) {
                continue;
            }
            // The caller's own signature covers this mock's host, so it is worthless
            // upstream and would only confuse the freshly computed one.
            if (signing && SigV4Signer.isStaleSigningHeader(lower)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            var enumeration = request.getHeaders(name);
            while (enumeration.hasMoreElements()) {
                values.add(enumeration.nextElement());
            }
            headers.put(name, values);
        }
        headers.put("Accept-Encoding", List.of("identity"));
        overrides.forEach((name, value) -> headers.put(name, List.of(value)));
        return headers;
    }

    private Map<String, List<String>> copyResponseHeaders(HttpResponse<InputStream> upstream,
                                                          HttpServletResponse response) {
        Map<String, List<String>> captured = new LinkedHashMap<>();
        upstream.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(":") || HOP_BY_HOP.contains(lower)) {
                return;
            }
            captured.put(name, values);
            values.forEach(value -> response.addHeader(name, value));
        });
        return captured;
    }

    /** Copies upstream to client while teeing into a buffer, flushing so SSE stays live. */
    private byte[] copyBody(InputStream source, OutputStream sink) throws IOException {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        try (source) {
            while ((read = source.read(chunk)) != -1) {
                sink.write(chunk, 0, read);
                sink.flush();
                captured.write(chunk, 0, read);
            }
        }
        sink.flush();
        return captured.toByteArray();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
