package com.example.llmmock.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.llmmock.config.LlmMockProperties;
import com.example.llmmock.core.Provider;

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

    private final LlmMockProperties properties;
    private final HttpClient httpClient;

    public UpstreamProxy(LlmMockProperties properties) {
        this.properties = properties;
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

        HttpRequest.Builder upstream = HttpRequest.newBuilder(uri)
                .timeout(properties.getProxy().getRequestTimeout())
                .method(request.getMethod(), body == null || body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        copyRequestHeaders(provider, request, upstream);

        HttpResponse<InputStream> upstreamResponse =
                httpClient.send(upstream.build(), HttpResponse.BodyHandlers.ofInputStream());

        Map<String, List<String>> responseHeaders = copyResponseHeaders(upstreamResponse, response);
        response.setStatus(upstreamResponse.statusCode());

        byte[] captured = copyBody(upstreamResponse.body(), response.getOutputStream());
        return new Result(upstreamResponse.statusCode(), responseHeaders, captured);
    }

    private void copyRequestHeaders(Provider provider, HttpServletRequest request,
                                    HttpRequest.Builder upstream) {
        Map<String, String> overrides = properties.getProxy().getHeaders()
                .getOrDefault(provider, Map.of());
        Set<String> overridden = overrides.keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());

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
            var values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                upstream.header(name, values.nextElement());
            }
        }
        upstream.header("Accept-Encoding", "identity");
        overrides.forEach(upstream::header);
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
