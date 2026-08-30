package io.github.ykwyuta.llmmock.sdk;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.ykwyuta.llmmock.core.Provider;
import io.github.ykwyuta.llmmock.store.StubRule;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the mock through the official AWS SDK for Java v2.
 *
 * <p>The streaming cases matter most here: Bedrock streams a binary
 * {@code application/vnd.amazon.eventstream} rather than SSE, and only the real SDK's
 * decoder can confirm the frames, headers and CRCs are actually well formed.
 */
class BedrockSdkTest extends SdkTest {

    private static final String CLAUDE = "anthropic.claude-sonnet-4-5-20250929-v1:0";

    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider
            .create(AwsBasicCredentials.create("test-access-key", "test-secret-key"));

    private BedrockRuntimeClient client() {
        return BedrockRuntimeClient.builder()
                .endpointOverride(URI.create(baseUrl("/bedrock")))
                .region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    private BedrockRuntimeAsyncClient asyncClient() {
        return BedrockRuntimeAsyncClient.builder()
                .endpointOverride(URI.create(baseUrl("/bedrock")))
                .region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .httpClient(NettyNioAsyncHttpClient.create())
                .build();
    }

    @Test
    void theSdkParsesAConverseResponse() {
        try (BedrockRuntimeClient client = client()) {
            ConverseResponse response = client.converse(request -> request
                    .modelId(CLAUDE)
                    .system(system -> system.text("Be concise."))
                    .messages(Message.builder()
                            .role(ConversationRole.USER)
                            .content(ContentBlock.fromText("Hello"))
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                            .maxTokens(128).temperature(0.5f).build()));

            assertThat(response.output().message().role()).isEqualTo(ConversationRole.ASSISTANT);
            assertThat(response.output().message().content().get(0).text())
                    .isEqualTo("[llm-mock] echo: Hello");
            assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
            assertThat(response.usage().totalTokens()).isPositive();
            assertThat(response.metrics().latencyMs()).isNotNegative();
        }
    }

    @Test
    void theSdkSurfacesAToolUseBlock() {
        StubRule rule = new StubRule();
        rule.setName("weather-tool");
        rule.setResponseText("");
        rule.setToolName("get_weather");
        rule.setToolArguments("{\"city\":\"Tokyo\"}");
        stubs.save(rule);

        try (BedrockRuntimeClient client = client()) {
            ConverseResponse response = client.converse(request -> request
                    .modelId(CLAUDE)
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText("weather?")).build()));

            assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
            var toolUse = response.output().message().content().get(0).toolUse();
            assertThat(toolUse.toolUseId()).startsWith("tooluse_");
            assertThat(toolUse.name()).isEqualTo("get_weather");
            assertThat(toolUse.input().asMap()).containsKey("city");
        }
    }

    @Test
    void theSdksEventStreamDecoderReadsTheConverseStream() throws Exception {
        StubRule rule = new StubRule();
        rule.setName("long-answer");
        rule.setResponseText("alpha beta gamma delta epsilon zeta");
        stubs.save(rule);

        StringBuilder assembled = new StringBuilder();
        AtomicReference<String> stopReason = new AtomicReference<>();
        AtomicReference<Integer> totalTokens = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (BedrockRuntimeAsyncClient client = asyncClient()) {
            ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                    .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                            .onContentBlockDelta(event -> assembled.append(event.delta().text()))
                            .onMessageStop(event -> stopReason.set(event.stopReasonAsString()))
                            .onMetadata(event -> totalTokens.set(event.usage().totalTokens()))
                            .build())
                    .onError(failure::set)
                    .build();

            client.converseStream(request -> request
                    .modelId(CLAUDE)
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText("go")).build()), handler)
                    .get();
        }

        // Any malformed frame, header or CRC would have surfaced here rather than as content.
        assertThat(failure.get()).isNull();
        assertThat(assembled.toString()).isEqualTo("alpha beta gamma delta epsilon zeta");
        assertThat(stopReason.get()).isEqualTo("end_turn");
        assertThat(totalTokens.get()).isPositive();
    }

    @Test
    void theSdkParsesAnInvokeModelResponseInTheModelsNativeFormat() {
        try (BedrockRuntimeClient client = client()) {
            InvokeModelResponse response = client.invokeModel(request -> request
                    .modelId(CLAUDE)
                    .contentType("application/json")
                    .body(SdkBytes.fromUtf8String("""
                            {"anthropic_version":"bedrock-2023-05-31","max_tokens":128,
                             "messages":[{"role":"user","content":"Hello"}]}""")));

            assertThat(response.body().asUtf8String())
                    .contains("\"type\":\"message\"")
                    .contains("[llm-mock] echo: Hello")
                    .contains("\"stop_reason\":\"end_turn\"");
        }
    }

    @Test
    void theSdksEventStreamDecoderReadsInvokeModelWithResponseStream() throws Exception {
        StubRule rule = new StubRule();
        rule.setName("long-answer");
        rule.setResponseText("one two three four five six");
        stubs.save(rule);

        StringBuilder assembled = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (BedrockRuntimeAsyncClient client = asyncClient()) {
            var handler = InvokeModelWithResponseStreamResponseHandler.builder()
                    .subscriber(InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
                            .onChunk(chunk -> assembled.append(chunk.bytes().asUtf8String()))
                            .build())
                    .onError(failure::set)
                    .build();

            client.invokeModelWithResponseStream(request -> request
                    .modelId(CLAUDE)
                    .contentType("application/json")
                    .body(SdkBytes.fromUtf8String("""
                            {"anthropic_version":"bedrock-2023-05-31","max_tokens":128,
                             "messages":[{"role":"user","content":"go"}]}""")), handler)
                    .get();
        }

        assertThat(failure.get()).isNull();
        // Each chunk's payload is the model's own native streaming event.
        assertThat(assembled.toString())
                .contains("message_start")
                .contains("content_block_delta")
                .contains("message_stop");
    }

    @Test
    void theSdkMapsA429OntoThrottlingException() {
        StubRule rule = new StubRule();
        rule.setName("throttle");
        rule.setHttpStatus(429);
        rule.setErrorMessage("Too many requests");
        stubs.save(rule);

        try (BedrockRuntimeClient client = client()) {
            assertThatThrownBy(() -> client.converse(request -> request
                    .modelId(CLAUDE)
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText("hi")).build())))
                    .isInstanceOf(ThrottlingException.class)
                    .hasMessageContaining("Too many requests");
        }
    }

    @Test
    void theSdkMapsA400OntoValidationException() {
        StubRule rule = new StubRule();
        rule.setName("invalid");
        rule.setHttpStatus(400);
        rule.setErrorMessage("bad input");
        stubs.save(rule);

        try (BedrockRuntimeClient client = client()) {
            assertThatThrownBy(() -> client.converse(request -> request
                    .modelId(CLAUDE)
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText("hi")).build())))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void whatTheSdkPutOnTheWireIsRecordedForLaterAssertions() {
        try (BedrockRuntimeClient client = client()) {
            client.converse(request -> request
                    .modelId(CLAUDE)
                    .system(system -> system.text("Be terse."))
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText("How is the weather?")).build())
                    .inferenceConfig(InferenceConfiguration.builder().maxTokens(256).build()));
        }

        assertThat(logs.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getProvider()).isEqualTo(Provider.BEDROCK);
            assertThat(entry.getEndpoint()).isEqualTo("converse");
            assertThat(entry.getModel()).isEqualTo(CLAUDE);
            assertThat(entry.getRequestBody())
                    .contains("How is the weather?")
                    .contains("Be terse.")
                    .contains("256");
        });
    }
}
