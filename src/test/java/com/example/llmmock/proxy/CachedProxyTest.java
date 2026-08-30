package com.example.llmmock.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.llmmock.support.AppInstances;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers CACHED_PROXY: proxy once, then answer repeats from the recording.
 *
 * <p>As everywhere else here the "real API" is another instance of this application, which
 * has a useful side effect: its own request log is an exact count of how many calls
 * actually reached it, which is the only way to prove a cache hit did not go upstream.
 */
class CachedProxyTest {

    @TempDir
    Path recordingsDir;

    private final AppInstances instances = new AppInstances();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stopAll() {
        instances.close();
    }

    private ConfigurableApplicationContext startUpstream() {
        return instances.start(Map.of(
                "llm-mock.default-response-template", "[upstream] answered: {{prompt}}"));
    }

    private ConfigurableApplicationContext startCachedProxy(String upstreamUrl, String ttl) {
        Map<String, String> properties = new java.util.LinkedHashMap<>(Map.of(
                "llm-mock.mode", "CACHED_PROXY",
                "llm-mock.proxy.recordings-dir", recordingsDir.toString(),
                "llm-mock.proxy.targets.openai", upstreamUrl + "/openai",
                "llm-mock.proxy.targets.bedrock", upstreamUrl + "/bedrock",
                "llm-mock.default-response-template", "[proxy-local] {{prompt}}"));
        if (ttl != null) {
            properties.put("llm-mock.proxy.cache.ttl", ttl);
        }
        return instances.start(properties);
    }

    private static final String CHAT_BODY = """
            {"model":"gpt-4o","messages":[{"role":"user","content":"Hello"}]}""";

    private HttpResponse<byte[]> post(String url, String body) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private String get(String url) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private String source(HttpResponse<byte[]> response) {
        return response.headers().firstValue("X-Llm-Mock-Source").orElse(null);
    }

    /** How many completion calls actually reached the upstream. */
    private int upstreamCallCount(ConfigurableApplicationContext upstream) {
        String requests = get(AppInstances.urlOf(upstream) + "/__admin/requests?limit=1000");
        return requests.split("\"endpoint\"", -1).length - 1;
    }

    private List<Path> recordingFiles() throws IOException {
        try (var files = Files.list(recordingsDir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).toList();
        }
    }

    // --- tests -------------------------------------------------------------------------

    @Test
    void theSecondIdenticalRequestIsServedFromCacheWithoutCallingUpstream() throws Exception {
        var upstream = startUpstream();
        var proxy = startCachedProxy(AppInstances.urlOf(upstream), null);
        String url = AppInstances.urlOf(proxy) + "/openai/v1/chat/completions";

        HttpResponse<byte[]> first = post(url, CHAT_BODY);
        assertThat(source(first)).isEqualTo("upstream");
        assertThat(text(first)).contains("[upstream] answered: Hello");
        assertThat(upstreamCallCount(upstream)).isEqualTo(1);

        HttpResponse<byte[]> second = post(url, CHAT_BODY);
        assertThat(source(second)).isEqualTo("cache");
        assertThat(text(second)).isEqualTo(text(first));
        // The count is unchanged, so the second answer really did not go upstream.
        assertThat(upstreamCallCount(upstream)).isEqualTo(1);
    }

    @Test
    void aDifferentRequestStillGoesUpstreamAndIsRecordedSeparately() throws Exception {
        var upstream = startUpstream();
        var proxy = startCachedProxy(AppInstances.urlOf(upstream), null);
        String url = AppInstances.urlOf(proxy) + "/openai/v1/chat/completions";

        post(url, CHAT_BODY);
        post(url, CHAT_BODY);
        HttpResponse<byte[]> other = post(url, """
                {"model":"gpt-4o","messages":[{"role":"user","content":"Goodbye"}]}""");

        assertThat(source(other)).isEqualTo("upstream");
        assertThat(text(other)).contains("answered: Goodbye");
        assertThat(upstreamCallCount(upstream)).isEqualTo(2);
        assertThat(recordingFiles()).hasSize(2);
    }

    @Test
    void aCacheHitStillWorksOnceTheUpstreamIsGone() throws Exception {
        var upstream = startUpstream();
        var proxy = startCachedProxy(AppInstances.urlOf(upstream), null);
        String url = AppInstances.urlOf(proxy) + "/openai/v1/chat/completions";
        String first = text(post(url, CHAT_BODY));

        upstream.close();

        HttpResponse<byte[]> cached = post(url, CHAT_BODY);
        assertThat(source(cached)).isEqualTo("cache");
        assertThat(text(cached)).isEqualTo(first);
    }

    @Test
    void aStaleRecordingIsRefreshedOnceTheTtlHasPassed() throws Exception {
        var upstream = startUpstream();
        // Everything recorded is immediately older than a 1ms TTL.
        var proxy = startCachedProxy(AppInstances.urlOf(upstream), "1ms");
        String url = AppInstances.urlOf(proxy) + "/openai/v1/chat/completions";

        post(url, CHAT_BODY);
        Thread.sleep(20);
        HttpResponse<byte[]> second = post(url, CHAT_BODY);

        assertThat(source(second)).isEqualTo("upstream");
        assertThat(upstreamCallCount(upstream)).isEqualTo(2);
    }

    @Test
    void aRecordingWithinTheTtlIsStillAHit() throws Exception {
        var upstream = startUpstream();
        var proxy = startCachedProxy(AppInstances.urlOf(upstream), "1h");
        String url = AppInstances.urlOf(proxy) + "/openai/v1/chat/completions";

        post(url, CHAT_BODY);
        assertThat(source(post(url, CHAT_BODY))).isEqualTo("cache");
        assertThat(upstreamCallCount(upstream)).isEqualTo(1);
    }

    @Test
    void streamedAnswersAreCachedByteForByte() throws Exception {
        var upstream = startUpstream();
        var proxy = startCachedProxy(AppInstances.urlOf(upstream), null);
        String url = AppInstances.urlOf(proxy) + "/openai/v1/chat/completions";
        String streaming = """
                {"model":"gpt-4o","messages":[{"role":"user","content":"one two three four"}],
                 "stream":true}""";

        HttpResponse<byte[]> first = post(url, streaming);
        HttpResponse<byte[]> second = post(url, streaming);

        assertThat(source(second)).isEqualTo("cache");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().firstValue("content-type").orElseThrow())
                .startsWith("text/event-stream");
    }

    @Test
    void theBinaryBedrockStreamIsCachedByteForByte() throws Exception {
        var upstream = startUpstream();
        var proxy = startCachedProxy(AppInstances.urlOf(upstream), null);
        String url = AppInstances.urlOf(proxy)
                + "/bedrock/model/anthropic.claude-sonnet-4-5-20250929-v1:0/converse-stream";
        String body = """
                {"messages":[{"role":"user","content":[{"text":"one two three"}]}]}""";

        HttpResponse<byte[]> first = post(url, body);
        HttpResponse<byte[]> second = post(url, body);

        assertThat(source(second)).isEqualTo("cache");
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    void aRecordingLeftBehindByAnEarlierRunIsAHitOnTheFirstCall() throws Exception {
        // The point of the mode: a suite converges on a complete fixture set by being run,
        // and the next run costs nothing.
        var upstream = startUpstream();
        var firstRun = startCachedProxy(AppInstances.urlOf(upstream), null);
        post(AppInstances.urlOf(firstRun) + "/openai/v1/chat/completions", CHAT_BODY);
        firstRun.close();

        var secondRun = startCachedProxy(AppInstances.urlOf(upstream), null);
        HttpResponse<byte[]> response =
                post(AppInstances.urlOf(secondRun) + "/openai/v1/chat/completions", CHAT_BODY);

        assertThat(source(response)).isEqualTo("cache");
        assertThat(upstreamCallCount(upstream)).isEqualTo(1);
    }
}
