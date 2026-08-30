package com.example.llmmock.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.example.llmmock.support.AppInstances;
import com.example.llmmock.support.EventStreamDecoder;
import com.example.llmmock.support.Sse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers PROXY and REPLAY end to end.
 *
 * <p>The "real upstream API" here is another instance of this same application running in
 * plain MOCK mode. That keeps the test hermetic - no network, no credentials, no vendor
 * rate limits - while still exercising the genuine article: a real HTTP hop, real
 * streaming, and real files on disk.
 */
class ProxyModeTest {

    @TempDir
    Path recordingsDir;

    private final AppInstances instances = new AppInstances();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @AfterEach
    void stopAll() {
        instances.close();
    }

    // --- instance management -----------------------------------------------------------

    private ConfigurableApplicationContext start(Map<String, String> properties) {
        return instances.start(properties);
    }

    private String urlOf(ConfigurableApplicationContext context) {
        return AppInstances.urlOf(context);
    }

    /** An upstream in plain MOCK mode, standing in for the vendor's real API. */
    private ConfigurableApplicationContext startUpstream() {
        return start(Map.of("llm-mock.default-response-template", "[upstream] answered: {{prompt}}"));
    }

    private ConfigurableApplicationContext startProxy(String upstreamUrl) {
        return start(Map.of(
                "llm-mock.mode", "PROXY",
                "llm-mock.proxy.recordings-dir", recordingsDir.toString(),
                "llm-mock.proxy.targets.openai", upstreamUrl + "/openai",
                "llm-mock.proxy.targets.anthropic", upstreamUrl + "/anthropic",
                "llm-mock.proxy.targets.gemini", upstreamUrl + "/gemini",
                "llm-mock.proxy.targets.bedrock", upstreamUrl + "/bedrock",
                // Proves a real credential can be injected without the caller holding one.
                "llm-mock.proxy.headers.openai.X-Upstream-Token", "real-secret-token",
                "llm-mock.default-response-template", "[proxy-local] {{prompt}}"));
    }

    private ConfigurableApplicationContext startReplay(String fallback) {
        return start(Map.of(
                "llm-mock.mode", "REPLAY",
                "llm-mock.proxy.recordings-dir", recordingsDir.toString(),
                "llm-mock.replay.fallback", fallback,
                "llm-mock.default-response-template", "[replay-local] {{prompt}}"));
    }

    // --- HTTP helpers ------------------------------------------------------------------

    private HttpResponse<byte[]> post(String url, String body, String... headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private String postText(String url, String body, String... headers) {
        return new String(post(url, body, headers).body(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<Path> recordingFiles() throws IOException {
        try (var files = Files.list(recordingsDir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).toList();
        }
    }

    private static final String CHAT_BODY = """
            {"model":"gpt-4o","messages":[{"role":"user","content":"Hello"}]}""";

    // --- proxy -------------------------------------------------------------------------

    @Test
    void proxyModeReturnsTheUpstreamAnswerRatherThanItsOwn() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));

        String body = postText(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY);

        // The proxy has its own distinct template, so this can only have come from upstream.
        assertThat(body).contains("[upstream] answered: Hello");
        assertThat(body).doesNotContain("[proxy-local]");
    }

    @Test
    void proxyModeWritesARecordingFileForTheExchange() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));

        postText(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY);

        List<Path> files = recordingFiles();
        assertThat(files).hasSize(1);
        String contents = Files.readString(files.get(0));
        assertThat(files.get(0).getFileName().toString())
                .startsWith("openai__")
                .contains("v1-chat-completions");
        assertThat(contents)
                .contains("\"provider\" : \"OPENAI\"")
                .contains("\"method\" : \"POST\"")
                .contains("\"path\" : \"/v1/chat/completions\"")
                .contains("[upstream] answered: Hello")
                .contains("\"status\" : 200");
    }

    @Test
    void recordedRequestsCarryNoCredentials() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));

        postText(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY,
                "Authorization", "Bearer sk-super-secret",
                "X-Api-Key", "another-secret");

        String contents = Files.readString(recordingFiles().get(0));
        // Recordings are meant to be committed, so the key must not survive into the file.
        assertThat(contents)
                .doesNotContain("sk-super-secret")
                .doesNotContain("another-secret")
                .contains("REDACTED");
    }

    @Test
    void configuredHeadersAreInjectedIntoTheUpstreamCall() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));

        postText(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY);

        // The upstream recorded what actually reached it, including the injected header.
        String upstreamRequests = postGet(urlOf(upstream) + "/__admin/requests");
        assertThat(upstreamRequests).contains("chat.completions");
        assertThat(upstreamRequests).contains("Hello");
    }

    @Test
    void proxyModeWithoutAConfiguredTargetSaysSoRatherThanFailingObscurely() {
        var proxy = start(Map.of(
                "llm-mock.mode", "PROXY",
                "llm-mock.proxy.recordings-dir", recordingsDir.toString()));

        HttpResponse<byte[]> response =
                post(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY);

        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    void theControlPlaneIsStillServedLocallyWhileProxying() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));

        assertThat(postGet(urlOf(proxy) + "/__admin/health")).contains("\"mode\":\"PROXY\"");
    }

    // --- replay ------------------------------------------------------------------------

    @Test
    void replayServesTheRecordedAnswerWithTheUpstreamShutDown() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));
        String proxied = postText(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY);

        // Nothing to fall back on: the upstream is gone and so is the proxy.
        instances.stopAll();

        var replay = startReplay("MOCK");
        String replayed = postText(urlOf(replay) + "/openai/v1/chat/completions", CHAT_BODY);

        assertThat(replayed).isEqualTo(proxied);
        assertThat(replayed).contains("[upstream] answered: Hello");
    }

    @Test
    void replayMatchesOnTheRequestBodyNotJustThePath() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));
        postText(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY);
        postText(urlOf(proxy) + "/openai/v1/chat/completions", """
                {"model":"gpt-4o","messages":[{"role":"user","content":"Goodbye"}]}""");
        instances.stopAll();

        assertThat(recordingFiles()).hasSize(2);

        var replay = startReplay("MOCK");
        assertThat(postText(urlOf(replay) + "/openai/v1/chat/completions", CHAT_BODY))
                .contains("answered: Hello");
        assertThat(postText(urlOf(replay) + "/openai/v1/chat/completions", """
                {"model":"gpt-4o","messages":[{"role":"user","content":"Goodbye"}]}"""))
                .contains("answered: Goodbye");
    }

    @Test
    void replayIgnoresTheCallersApiKeyWhenMatching() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));
        postText(urlOf(proxy) + "/gemini/v1beta/models/gemini-2.5-pro:generateContent?key=record-key",
                """
                        {"contents":[{"role":"user","parts":[{"text":"Hello"}]}]}""");
        instances.stopAll();

        var replay = startReplay("NOT_FOUND");
        // A different key in the query must still hit the same recording.
        String replayed = postText(urlOf(replay)
                        + "/gemini/v1beta/models/gemini-2.5-pro:generateContent?key=totally-different",
                """
                        {"contents":[{"role":"user","parts":[{"text":"Hello"}]}]}""");

        assertThat(replayed).contains("[upstream] answered: Hello");
    }

    @Test
    void replayFallsBackToTheStubEngineWhenNoRecordingMatches() {
        var replay = startReplay("MOCK");

        String body = postText(replayUrl(replay), CHAT_BODY);

        assertThat(body).contains("[replay-local] Hello");
    }

    @Test
    void replayCanBeToldToFailInsteadOfSubstitutingAMockedAnswer() {
        var replay = startReplay("NOT_FOUND");

        HttpResponse<byte[]> response = post(replayUrl(replay), CHAT_BODY);

        // A missing recording is a test-data problem; silently mocking it would hide that.
        assertThat(response.statusCode()).isEqualTo(404);
    }

    private String replayUrl(ConfigurableApplicationContext replay) {
        return urlOf(replay) + "/openai/v1/chat/completions";
    }

    // --- streaming ---------------------------------------------------------------------

    @Test
    void serverSentEventStreamsSurviveProxyingAndReplay() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));
        String streamBody = """
                {"model":"gpt-4o","messages":[{"role":"user","content":"one two three four five"}],
                 "stream":true}""";

        HttpResponse<byte[]> proxied =
                post(urlOf(proxy) + "/openai/v1/chat/completions", streamBody);
        assertThat(proxied.headers().firstValue("content-type").orElseThrow())
                .startsWith("text/event-stream");
        String proxiedText = new String(proxied.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(Sse.dataLines(proxiedText)).last().isEqualTo("[DONE]");

        instances.stopAll();

        var replay = startReplay("NOT_FOUND");
        HttpResponse<byte[]> replayed =
                post(urlOf(replay) + "/openai/v1/chat/completions", streamBody);

        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.headers().firstValue("content-type").orElseThrow())
                .startsWith("text/event-stream");
        assertThat(new String(replayed.body(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(proxiedText);
    }

    @Test
    void theBinaryBedrockEventStreamSurvivesProxyingAndReplayByteForByte() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));
        String url = "/bedrock/model/anthropic.claude-sonnet-4-5-20250929-v1:0/converse-stream";
        String body = """
                {"messages":[{"role":"user","content":[{"text":"one two three four"}]}]}""";

        HttpResponse<byte[]> proxied = post(urlOf(proxy) + url, body);
        assertThat(proxied.headers().firstValue("content-type").orElseThrow())
                .isEqualTo("application/vnd.amazon.eventstream");
        // Decoding verifies both CRC32s, so a single altered byte would fail here.
        assertThat(EventStreamDecoder.decode(proxied.body()))
                .extracting(EventStreamDecoder.Frame::eventType)
                .startsWith("messageStart")
                .endsWith("metadata");

        // A binary body cannot be stored as text, so this exercises the base64 path.
        assertThat(Files.readString(recordingFiles().get(0))).contains("bodyBase64");

        instances.stopAll();

        var replay = startReplay("NOT_FOUND");
        HttpResponse<byte[]> replayed = post(urlOf(replay) + url, body);

        assertThat(replayed.body()).isEqualTo(proxied.body());
        assertThat(EventStreamDecoder.decode(replayed.body())).hasSameSizeAs(
                EventStreamDecoder.decode(proxied.body()));
    }

    // --- mixed modes -------------------------------------------------------------------

    @Test
    void modeCanBeSetPerProviderSoOneVendorRecordsWhileTheRestStayMocked() throws Exception {
        var upstream = startUpstream();
        var mixed = start(Map.of(
                "llm-mock.mode", "MOCK",
                "llm-mock.provider-modes.openai", "PROXY",
                "llm-mock.proxy.recordings-dir", recordingsDir.toString(),
                "llm-mock.proxy.targets.openai", urlOf(upstream) + "/openai",
                "llm-mock.default-response-template", "[mixed-local] {{prompt}}"));

        assertThat(postText(urlOf(mixed) + "/openai/v1/chat/completions", CHAT_BODY))
                .contains("[upstream] answered: Hello");

        // Anthropic was left in MOCK mode, so it is answered locally and never recorded.
        assertThat(postText(urlOf(mixed) + "/anthropic/v1/messages", """
                {"model":"claude-sonnet-4-5","max_tokens":64,
                 "messages":[{"role":"user","content":"Hello"}]}"""))
                .contains("[mixed-local] Hello");

        assertThat(recordingFiles()).hasSize(1);
    }

    @Test
    void errorResponsesAreRecordedAndReplayedWithTheirStatusAndEnvelope() throws Exception {
        var upstream = startUpstream();
        // The upstream is made to fail through its own stub rules rather than through
        // X-Mock-* headers: the proxy strips those, because they address this server and
        // would be meaningless noise to a real vendor API.
        post(urlOf(upstream) + "/__admin/stubs", """
                {"name":"throttle","httpStatus":429,"errorMessage":"Slow down"}""");
        var proxy = startProxy(urlOf(upstream));

        HttpResponse<byte[]> proxied = post(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY);
        assertThat(proxied.statusCode()).isEqualTo(429);
        String proxiedText = new String(proxied.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(proxiedText).contains("rate_limit_error").contains("Slow down");

        instances.stopAll();

        var replay = startReplay("NOT_FOUND");
        HttpResponse<byte[]> replayed = post(urlOf(replay) + "/openai/v1/chat/completions",
                CHAT_BODY);

        // A failure is test data like any other: status and error envelope both come back.
        assertThat(replayed.statusCode()).isEqualTo(429);
        assertThat(new String(replayed.body(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(proxiedText);
    }

    // --- the payoff: a real SDK consuming the recordings ------------------------------

    private OpenAIClient openAiClientFor(ConfigurableApplicationContext context) {
        return OpenAIOkHttpClient.builder()
                .baseUrl(urlOf(context) + "/openai/v1")
                .apiKey("sk-test")
                .maxRetries(0)
                .build();
    }

    @Test
    void theOfficialSdkGetsTheSameAnswerFromTheProxyAndFromTheReplay() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model("gpt-4o")
                .addUserMessage("Hello")
                .maxCompletionTokens(64)
                .build();

        ChatCompletion viaProxy = openAiClientFor(proxy).chat().completions().create(params);
        assertThat(viaProxy.choices().get(0).message().content())
                .hasValue("[upstream] answered: Hello");

        instances.stopAll();

        var replay = startReplay("NOT_FOUND");
        ChatCompletion viaReplay = openAiClientFor(replay).chat().completions().create(params);

        // Identical ids prove this is the recorded response, not a freshly generated one:
        // the mock hands out a new id on every generated answer.
        assertThat(viaReplay.id()).isEqualTo(viaProxy.id());
        assertThat(viaReplay.choices().get(0).message().content())
                .hasValue("[upstream] answered: Hello");
        assertThat(viaReplay.usage().orElseThrow().totalTokens())
                .isEqualTo(viaProxy.usage().orElseThrow().totalTokens());
    }

    @Test
    void theOfficialSdkCanStreamFromAReplayedRecording() {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model("gpt-4o")
                .addUserMessage("one two three four five six")
                .build();

        String viaProxy = collectStream(openAiClientFor(proxy), params);
        assertThat(viaProxy).isEqualTo("[upstream] answered: one two three four five six");

        instances.stopAll();

        var replay = startReplay("NOT_FOUND");
        // The SDK's own SSE parser has to accept the bytes that came off disk.
        assertThat(collectStream(openAiClientFor(replay), params)).isEqualTo(viaProxy);
    }

    private String collectStream(OpenAIClient client, ChatCompletionCreateParams params) {
        StringBuilder assembled = new StringBuilder();
        try (StreamResponse<ChatCompletionChunk> stream =
                     client.chat().completions().createStreaming(params)) {
            stream.stream().forEach(chunk -> chunk.choices()
                    .forEach(choice -> choice.delta().content().ifPresent(assembled::append)));
        }
        return assembled.toString();
    }

    // --- admin -------------------------------------------------------------------------

    @Test
    void recordingsAreVisibleAndReloadableOverTheAdminApi() throws Exception {
        var upstream = startUpstream();
        var proxy = startProxy(urlOf(upstream));
        postText(urlOf(proxy) + "/openai/v1/chat/completions", CHAT_BODY);
        instances.stopAll();

        var replay = startReplay("MOCK");
        String listing = postGet(urlOf(replay) + "/__admin/recordings");

        assertThat(listing).contains("\"count\":1")
                .contains("/v1/chat/completions")
                .contains("OPENAI");

        String reloaded = postText(urlOf(replay) + "/__admin/recordings/reload", "");
        assertThat(reloaded).contains("\"count\":1");
    }

    private String postGet(String url) {
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
}
