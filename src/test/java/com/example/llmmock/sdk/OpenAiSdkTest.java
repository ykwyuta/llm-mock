package com.example.llmmock.sdk;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.llmmock.core.Provider;
import com.example.llmmock.store.StubRule;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.errors.NotFoundException;
import com.openai.errors.RateLimitException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.embeddings.EmbeddingCreateParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drives the mock through the official {@code com.openai:openai-java} client. */
class OpenAiSdkTest extends SdkTest {

    private OpenAIClient client() {
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl("/openai/v1"))
                .apiKey("sk-test")
                // Retries would mask a genuine failure behind a later success.
                .maxRetries(0)
                .build();
    }

    @Test
    void theSdkParsesANonStreamingChatCompletion() {
        ChatCompletion completion = client().chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("gpt-4o")
                        .addSystemMessage("Be concise.")
                        .addUserMessage("Hello")
                        .maxCompletionTokens(64)
                        .build());

        assertThat(completion.id()).startsWith("chatcmpl-");
        assertThat(completion.model()).isEqualTo("gpt-4o");
        assertThat(completion.choices()).hasSize(1);
        assertThat(completion.choices().get(0).message().content()).hasValue("[llm-mock] echo: Hello");
        assertThat(completion.choices().get(0).finishReason())
                .isEqualTo(ChatCompletion.Choice.FinishReason.STOP);
        assertThat(completion.usage()).isPresent();
        assertThat(completion.usage().get().totalTokens()).isPositive();
    }

    @Test
    void aStubRegisteredInJavaDrivesWhatTheSdkSees() {
        StubRule rule = new StubRule();
        rule.setName("greeting");
        rule.setProvider(Provider.OPENAI);
        rule.setPromptPattern("(?i)hello");
        rule.setResponseText("Hi from the stub.");
        stubs.save(rule);

        ChatCompletion completion = client().chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("gpt-4o").addUserMessage("Hello").build());

        assertThat(completion.choices().get(0).message().content()).hasValue("Hi from the stub.");
    }

    @Test
    void theSdksSseParserReassemblesTheStreamedAnswer() {
        StubRule rule = new StubRule();
        rule.setName("long-answer");
        rule.setResponseText("one two three four five six seven eight");
        stubs.save(rule);

        List<ChatCompletionChunk> chunks = new ArrayList<>();
        try (StreamResponse<ChatCompletionChunk> stream = client().chat().completions()
                .createStreaming(ChatCompletionCreateParams.builder()
                        .model("gpt-4o").addUserMessage("go").build())) {
            stream.stream().forEach(chunks::add);
        }

        StringBuilder assembled = new StringBuilder();
        for (ChatCompletionChunk chunk : chunks) {
            chunk.choices().forEach(choice ->
                    choice.delta().content().ifPresent(assembled::append));
        }

        assertThat(assembled.toString()).isEqualTo("one two three four five six seven eight");
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(chunks.size() - 1).choices().get(0).finishReason())
                .isPresent();
    }

    @Test
    void theSdkSurfacesAToolCallWithItsArguments() {
        StubRule rule = new StubRule();
        rule.setName("weather-tool");
        rule.setResponseText("");
        rule.setToolName("get_weather");
        rule.setToolArguments("{\"city\":\"Tokyo\"}");
        stubs.save(rule);

        ChatCompletion completion = client().chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("gpt-4o").addUserMessage("weather?").build());

        assertThat(completion.choices().get(0).finishReason())
                .isEqualTo(ChatCompletion.Choice.FinishReason.TOOL_CALLS);
        var toolCalls = completion.choices().get(0).message().toolCalls().orElseThrow();
        assertThat(toolCalls).hasSize(1);
        var functionCall = toolCalls.get(0).function().orElseThrow();
        assertThat(functionCall.id()).startsWith("call_");
        assertThat(functionCall.function().name()).isEqualTo("get_weather");
        assertThat(functionCall.function().arguments()).isEqualTo("{\"city\":\"Tokyo\"}");
    }

    @Test
    void theSdkMapsA429OntoItsOwnRateLimitException() {
        StubRule rule = new StubRule();
        rule.setName("throttle");
        rule.setHttpStatus(429);
        rule.setErrorMessage("Rate limit reached");
        stubs.save(rule);

        assertThatThrownBy(() -> client().chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("gpt-4o").addUserMessage("hi").build()))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("Rate limit reached");
    }

    @Test
    void theSdkMapsA404OntoItsOwnNotFoundException() {
        assertThatThrownBy(() -> client().models().retrieve(
                com.openai.models.models.ModelRetrieveParams.builder()
                        .model("no-such-model").build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theSdkListsModels() {
        var page = client().models().list();

        assertThat(page.data()).isNotEmpty();
        assertThat(page.data()).extracting(model -> model.id()).contains("gpt-4o");
    }

    @Test
    void theSdkParsesAnEmbeddingResponse() {
        var response = client().embeddings().create(EmbeddingCreateParams.builder()
                .model("text-embedding-3-small")
                .input("hello")
                .build());

        assertThat(response.data()).hasSize(1);
        // 8 dimensions come from the test profile.
        assertThat(response.data().get(0).embedding()).hasSize(8);
        assertThat(response.usage().promptTokens()).isPositive();
    }

    @Test
    void whatTheSdkPutOnTheWireIsRecordedForLaterAssertions() {
        client().chat().completions().create(ChatCompletionCreateParams.builder()
                .model("gpt-4o")
                .addSystemMessage("Be terse.")
                .addUserMessage("How is the weather?")
                .temperature(0.25)
                .maxCompletionTokens(128)
                .build());

        assertThat(logs.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getProvider()).isEqualTo(Provider.OPENAI);
            assertThat(entry.getEndpoint()).isEqualTo("chat.completions");
            assertThat(entry.getModel()).isEqualTo("gpt-4o");
            // The SDK's own serialisation, not something the test hand-wrote.
            assertThat(entry.getRequestBody())
                    .contains("\"How is the weather?\"")
                    .contains("\"Be terse.\"")
                    .contains("\"temperature\":0.25")
                    .contains("\"max_completion_tokens\":128");
        });
    }
}
