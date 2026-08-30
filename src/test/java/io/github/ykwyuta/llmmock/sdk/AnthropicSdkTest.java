package io.github.ykwyuta.llmmock.sdk;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import io.github.ykwyuta.llmmock.core.Provider;
import io.github.ykwyuta.llmmock.store.StubRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drives the mock through the official {@code com.anthropic:anthropic-java} client. */
class AnthropicSdkTest extends SdkTest {

    private AnthropicClient client() {
        return AnthropicOkHttpClient.builder()
                .baseUrl(baseUrl("/anthropic"))
                .apiKey("sk-ant-test")
                .maxRetries(0)
                .build();
    }

    @Test
    void theSdkParsesANonStreamingMessage() {
        Message message = client().messages().create(MessageCreateParams.builder()
                .model("claude-sonnet-4-5")
                .maxTokens(64)
                .system("Be concise.")
                .addUserMessage("Hello")
                .build());

        assertThat(message.id()).startsWith("msg_");
        assertThat(message.model().toString()).isEqualTo("claude-sonnet-4-5");
        assertThat(message.content()).hasSize(1);
        assertThat(message.content().get(0).asText().text()).isEqualTo("[llm-mock] echo: Hello");
        assertThat(message.stopReason()).hasValue(StopReason.END_TURN);
        assertThat(message.usage().inputTokens()).isPositive();
        assertThat(message.usage().outputTokens()).isPositive();
    }

    @Test
    void theSdksSseParserReassemblesTheStreamedAnswer() {
        StubRule rule = new StubRule();
        rule.setName("long-answer");
        rule.setResponseText("alpha beta gamma delta epsilon zeta");
        stubs.save(rule);

        List<RawMessageStreamEvent> events = new ArrayList<>();
        try (StreamResponse<RawMessageStreamEvent> stream = client().messages()
                .createStreaming(MessageCreateParams.builder()
                        .model("claude-sonnet-4-5").maxTokens(64).addUserMessage("go").build())) {
            stream.stream().forEach(events::add);
        }

        // The SDK only produces these if every named event and its payload parsed cleanly.
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).isMessageStart()).isTrue();
        assertThat(events.get(events.size() - 1).isMessageStop()).isTrue();
        assertThat(events).anyMatch(RawMessageStreamEvent::isContentBlockStart);
        assertThat(events).anyMatch(RawMessageStreamEvent::isMessageDelta);

        StringBuilder assembled = new StringBuilder();
        for (RawMessageStreamEvent event : events) {
            event.contentBlockDelta().ifPresent(delta ->
                    delta.delta().text().ifPresent(text -> assembled.append(text.text())));
        }
        assertThat(assembled.toString()).isEqualTo("alpha beta gamma delta epsilon zeta");
    }

    @Test
    void theSdkSurfacesAToolUseBlockWithObjectInput() {
        StubRule rule = new StubRule();
        rule.setName("weather-tool");
        rule.setResponseText("");
        rule.setToolName("get_weather");
        rule.setToolArguments("{\"city\":\"Tokyo\"}");
        stubs.save(rule);

        Message message = client().messages().create(MessageCreateParams.builder()
                .model("claude-sonnet-4-5").maxTokens(64).addUserMessage("weather?").build());

        assertThat(message.stopReason()).hasValue(StopReason.TOOL_USE);
        var toolUse = message.content().get(0).asToolUse();
        assertThat(toolUse.id()).startsWith("toolu_");
        assertThat(toolUse.name()).isEqualTo("get_weather");
        assertThat(toolUse._input().toString()).contains("Tokyo");
    }

    @Test
    void theSdkMapsA429OntoItsOwnRateLimitException() {
        StubRule rule = new StubRule();
        rule.setName("throttle");
        rule.setHttpStatus(429);
        rule.setErrorMessage("Too many requests");
        stubs.save(rule);

        assertThatThrownBy(() -> client().messages().create(MessageCreateParams.builder()
                .model("claude-sonnet-4-5").maxTokens(64).addUserMessage("hi").build()))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("Too many requests");
    }

    @Test
    void theSdkMapsAValidationFailureOntoBadRequest() {
        StubRule rule = new StubRule();
        rule.setName("bad-request");
        rule.setHttpStatus(400);
        rule.setErrorMessage("something is wrong with the request");
        stubs.save(rule);

        assertThatThrownBy(() -> client().messages().create(MessageCreateParams.builder()
                .model("claude-sonnet-4-5").maxTokens(64).addUserMessage("hi").build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void theSdkParsesTheTokenCountResponse() {
        var count = client().messages().countTokens(MessageCountTokensParams.builder()
                .model("claude-sonnet-4-5")
                .addUserMessage("Hello there")
                .build());

        assertThat(count.inputTokens()).isPositive();
    }

    @Test
    void theSdkListsModels() {
        var page = client().models().list();

        assertThat(page.data()).isNotEmpty();
        assertThat(page.data()).extracting(model -> model.id()).contains("claude-opus-4-5");
    }

    @Test
    void whatTheSdkPutOnTheWireIsRecordedForLaterAssertions() {
        client().messages().create(MessageCreateParams.builder()
                .model("claude-sonnet-4-5")
                .maxTokens(128)
                .system("Be terse.")
                .addUserMessage("How is the weather?")
                .temperature(0.25)
                .build());

        assertThat(logs.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getProvider()).isEqualTo(Provider.ANTHROPIC);
            assertThat(entry.getEndpoint()).isEqualTo("messages");
            assertThat(entry.getRequestBody())
                    .contains("\"How is the weather?\"")
                    .contains("\"Be terse.\"")
                    .contains("\"max_tokens\":128");
        });
    }
}
