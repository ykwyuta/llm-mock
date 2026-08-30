package io.github.ykwyuta.llmmock.proxy;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.ykwyuta.llmmock.config.CachedBodyRequest;
import io.github.ykwyuta.llmmock.config.LlmMockProperties;
import io.github.ykwyuta.llmmock.core.MockMode;
import io.github.ykwyuta.llmmock.core.Provider;
import io.github.ykwyuta.llmmock.store.RequestRecorder;
import io.github.ykwyuta.llmmock.usage.UsageExtractor;
import io.github.ykwyuta.llmmock.usage.UsageSource;
import io.github.ykwyuta.llmmock.usage.UsageTracker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Decides, per request, whether the mock answers, the real upstream answers, or a
 * previously recorded answer is replayed.
 *
 * <p>This lives in a filter rather than in the four controllers on purpose: proxying and
 * replaying are byte-level concerns that apply identically to every endpoint of every
 * provider, streaming ones included. Putting the logic here means a new endpoint is
 * proxyable the moment it exists, with no extra work.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProxyReplayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyReplayFilter.class);

    /** Names the answer's origin on the response, so a cache hit is visible to the caller. */
    public static final String SOURCE_HEADER = "X-Llm-Mock-Source";

    private final LlmMockProperties properties;
    private final UpstreamProxy upstream;
    private final RecordingStore recordings;
    private final RequestRecorder requestRecorder;
    private final UsageExtractor usageExtractor;
    private final UsageTracker usageTracker;

    public ProxyReplayFilter(LlmMockProperties properties, UpstreamProxy upstream,
                             RecordingStore recordings, RequestRecorder requestRecorder,
                             UsageExtractor usageExtractor, UsageTracker usageTracker) {
        this.properties = properties;
        this.upstream = upstream;
        this.recordings = recordings;
        this.requestRecorder = requestRecorder;
        this.usageExtractor = usageExtractor;
        this.usageTracker = usageTracker;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The control plane is always served locally, whatever mode the providers are in.
        return !properties.anyNonMockMode()
                || request.getRequestURI().startsWith("/__admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ProviderPath resolved = resolve(request.getRequestURI());
        if (resolved == null) {
            chain.doFilter(request, response);
            return;
        }
        MockMode mode = properties.modeFor(resolved.provider());
        if (mode == MockMode.MOCK) {
            chain.doFilter(request, response);
            return;
        }

        byte[] body = bodyOf(request);
        String key = RecordingKey.of(resolved.provider(), request.getMethod(),
                resolved.upstreamPath(), request.getQueryString(), body,
                properties.getProxy().getRedactQueryParams());

        if (mode == MockMode.REPLAY || mode == MockMode.CACHED_PROXY) {
            Optional<Recording> match = recordings.find(key).filter(this::isFresh);
            if (match.isPresent()) {
                // A cache hit in CACHED_PROXY is money not spent; a hit in REPLAY is just
                // offline test data. They are tracked separately for exactly that reason.
                writeRecorded(resolved.provider(), request, match.get(), response,
                        mode == MockMode.CACHED_PROXY ? UsageSource.CACHE : UsageSource.RECORDING);
                return;
            }
            if (mode == MockMode.REPLAY) {
                if (properties.getReplay().getFallback()
                        == LlmMockProperties.Replay.Fallback.NOT_FOUND) {
                    log.warn("No recording for {} {} (key {})", request.getMethod(),
                            resolved.upstreamPath(), key);
                    response.sendError(HttpServletResponse.SC_NOT_FOUND,
                            "No recording for key " + key);
                    return;
                }
                // Falling through to the stub engine keeps a partially recorded suite usable.
                log.debug("No recording for key {}; falling back to the mock engine", key);
                chain.doFilter(request, response);
                return;
            }
            log.debug("Cache miss for key {}; calling upstream once and recording it", key);
        }

        proxy(resolved, request, response, body, key);
    }

    /** A recording is stale once it is older than the configured cache TTL. */
    private boolean isFresh(Recording recording) {
        Duration ttl = properties.getProxy().getCache().getTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative() || recording.recordedAt() == null) {
            return true;
        }
        return recording.recordedAt().plus(ttl).isAfter(Instant.now());
    }

    // --- proxy ------------------------------------------------------------------------

    private void proxy(ProviderPath resolved, HttpServletRequest request,
                       HttpServletResponse response, byte[] body, String key) throws IOException {
        if (!upstream.hasTarget(resolved.provider())) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "PROXY mode is on for " + resolved.provider()
                            + " but llm-mock.proxy.targets." + resolved.provider().name().toLowerCase()
                            + " is not set");
            return;
        }
        setSourceHeader(response, UsageSource.UPSTREAM);
        UpstreamProxy.Result result;
        try {
            result = upstream.forward(resolved.provider(), request, resolved.upstreamPath(), body,
                    response);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Proxying was interrupted", ex);
        } catch (IOException ex) {
            log.warn("Proxying {} {} failed: {}", request.getMethod(), resolved.upstreamPath(),
                    ex.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY,
                        "Upstream call failed: " + ex.getMessage());
            }
            return;
        }

        if (properties.getProxy().isRecord()) {
            recordings.save(new Recording(key, resolved.provider(), Instant.now(),
                    new Recording.RecordedRequest(request.getMethod(), resolved.upstreamPath(),
                            redactQuery(request.getQueryString()), redactedHeaders(request),
                            body.length == 0 ? null : new String(body,
                                    java.nio.charset.StandardCharsets.UTF_8)),
                    Recording.response(result.status(), result.headers(), result.body())));
        }
        recordUsage(resolved, UsageSource.UPSTREAM, headerOf(result.headers(), "content-type"),
                result.body());
        logInteraction(resolved, request, result.status(), "proxied upstream");
    }

    // --- replay -----------------------------------------------------------------------

    private void writeRecorded(Provider provider, HttpServletRequest request, Recording recording,
                               HttpServletResponse response, UsageSource source) throws IOException {
        Recording.RecordedResponse recorded = recording.response();
        setSourceHeader(response, source);
        response.setStatus(recorded.status());
        if (recorded.headers() != null) {
            recorded.headers().forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                // Content-Length is recomputed and transfer framing is this hop's business.
                if (lower.equals("content-length") || lower.equals("transfer-encoding")
                        || lower.startsWith(":")) {
                    return;
                }
                values.forEach(value -> response.addHeader(name, value));
            });
        }
        byte[] bytes = recorded.bytes();
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
        recordUsage(new ProviderPath(provider, recording.request().path()), source,
                recorded.contentType(), bytes);
        logInteraction(new ProviderPath(provider, recording.request().path()), request,
                recorded.status(), source == UsageSource.CACHE
                        ? "cache hit " + recording.key()
                        : "replayed recording " + recording.key());
    }

    // --- accounting -------------------------------------------------------------------

    private void setSourceHeader(HttpServletResponse response, UsageSource source) {
        if (properties.getProxy().getCache().isSourceHeader()) {
            response.setHeader(SOURCE_HEADER, source.name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Pulls the token counts out of the answer. In proxy mode the response was produced
     * upstream, so its body is the only place those numbers exist.
     */
    private void recordUsage(ProviderPath resolved, UsageSource source, String contentType,
                             byte[] body) {
        usageExtractor.extract(resolved.provider(), resolved.upstreamPath(), contentType, body)
                .ifPresent(extracted -> usageTracker.record(resolved.provider(), extracted.model(),
                        resolved.upstreamPath(), isStreaming(contentType), source,
                        extracted.usage()));
    }

    private boolean isStreaming(String contentType) {
        if (contentType == null) {
            return false;
        }
        String value = contentType.toLowerCase(Locale.ROOT);
        return value.startsWith("text/event-stream") || value.contains("vnd.amazon.eventstream");
    }

    private static String headerOf(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name) && !header.getValue().isEmpty()) {
                return header.getValue().get(0);
            }
        }
        return null;
    }

    // --- helpers ----------------------------------------------------------------------

    private byte[] bodyOf(HttpServletRequest request) throws IOException {
        CachedBodyRequest cached = CachedBodyRequest.find(request);
        if (cached != null) {
            return cached.body();
        }
        return request.getInputStream().readAllBytes();
    }

    /** Maps an inbound URI onto the provider serving it and the path the upstream expects. */
    ProviderPath resolve(String uri) {
        Provider match = null;
        String longestPrefix = null;
        for (Map.Entry<String, Provider> entry : prefixes().entrySet()) {
            String prefix = entry.getKey();
            if (uri.equals(prefix) || uri.startsWith(prefix + "/")) {
                if (longestPrefix == null || prefix.length() > longestPrefix.length()) {
                    longestPrefix = prefix;
                    match = entry.getValue();
                }
            }
        }
        if (match == null) {
            return null;
        }
        String remainder = uri.substring(longestPrefix.length());
        return new ProviderPath(match, remainder.isEmpty() ? "/" : remainder);
    }

    private Map<String, Provider> prefixes() {
        Map<String, Provider> prefixes = new LinkedHashMap<>();
        addPrefix(prefixes, properties.getPaths().getOpenai(), Provider.OPENAI);
        addPrefix(prefixes, properties.getPaths().getAnthropic(), Provider.ANTHROPIC);
        addPrefix(prefixes, properties.getPaths().getGemini(), Provider.GEMINI);
        addPrefix(prefixes, properties.getPaths().getBedrock(), Provider.BEDROCK);
        return prefixes;
    }

    private void addPrefix(Map<String, Provider> prefixes, String prefix, Provider provider) {
        // A provider mounted at the root cannot be told apart by path, so it is never
        // proxied. This is documented rather than guessed at.
        if (prefix == null || prefix.isBlank() || "/".equals(prefix)) {
            return;
        }
        prefixes.put(prefix.startsWith("/") ? prefix : "/" + prefix, provider);
    }

    /** Captures the request headers, with credential values replaced by a placeholder. */
    private Map<String, List<String>> redactedHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            boolean redacted = isRedacted(name);
            List<String> values = new ArrayList<>();
            var enumeration = request.getHeaders(name);
            while (enumeration.hasMoreElements()) {
                String value = enumeration.nextElement();
                values.add(redacted ? "REDACTED" : value);
            }
            headers.put(name, values);
        }
        return headers;
    }

    private boolean isRedacted(String header) {
        return properties.getProxy().getRedactHeaders().stream()
                .anyMatch(candidate -> candidate.equalsIgnoreCase(header));
    }

    /** Blanks the values of sensitive query parameters so a recording is safe to commit. */
    private String redactQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            boolean redacted = properties.getProxy().getRedactQueryParams().stream()
                    .anyMatch(candidate -> candidate.equalsIgnoreCase(name));
            parts.add(redacted ? name + "=REDACTED" : pair);
        }
        return String.join("&", parts);
    }

    private void logInteraction(ProviderPath resolved, HttpServletRequest request, int status,
                                String summary) {
        requestRecorder.record(resolved.provider(), resolved.upstreamPath(), null, false, status,
                null, null, null, summary);
    }

    /** A resolved inbound URI: which provider serves it, and the path minus the prefix. */
    record ProviderPath(Provider provider, String upstreamPath) {
    }
}
