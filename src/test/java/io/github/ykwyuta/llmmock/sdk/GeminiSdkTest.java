package io.github.ykwyuta.llmmock.sdk;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ykwyuta.llmmock.core.Provider;
import io.github.ykwyuta.llmmock.store.StubRule;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drives the mock through the official {@code com.google.genai:google-genai} client. */
class GeminiSdkTest extends SdkTest {

    private Client client() {
        return Client.builder()
                .apiKey("test-key")
                .httpOptions(HttpOptions.builder()
                        .baseUrl(baseUrl("/gemini"))
                        .apiVersion("v1beta")
                        // A single attempt: retries would both slow the suite down and
                        // mask a genuine failure behind a later success.
                        .retryOptions(HttpRetryOptions.builder().attempts(1))
                        .build())
                .build();
    }

    @Test
    void theSdkParsesANonStreamingGenerateContentResponse() {
        try (Client client = client()) {
            GenerateContentResponse response =
                    client.models.generateContent("gemini-2.5-pro", "Hello", null);

            assertThat(response.text()).isEqualTo("[llm-mock] echo: Hello");
            assertThat(response.candidates()).isPresent();
            assertThat(response.candidates().get()).hasSize(1);
            assertThat(response.candidates().get().get(0).finishReason()).isPresent();
            assertThat(response.usageMetadata()).isPresent();
            assertThat(response.usageMetadata().get().totalTokenCount().orElseThrow()).isPositive();
        }
    }

    @Test
    void theSdkAppliesTheGenerationConfigAndSystemInstruction() {
        try (Client client = client()) {
            GenerateContentResponse response = client.models.generateContent("gemini-2.5-flash",
                    "How is the weather?",
                    GenerateContentConfig.builder()
                            .systemInstruction(com.google.genai.types.Content.fromParts(
                                    com.google.genai.types.Part.fromText("Be terse.")))
                            .temperature(0.25f)
                            .maxOutputTokens(128)
                            .build());

            assertThat(response.text()).isEqualTo("[llm-mock] echo: How is the weather?");
        }

        assertThat(logs.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getProvider()).isEqualTo(Provider.GEMINI);
            assertThat(entry.getModel()).isEqualTo("gemini-2.5-flash");
            // Serialised by the SDK itself, so this proves the mock reads what it really sends.
            assertThat(entry.getRequestBody())
                    .contains("How is the weather?")
                    .contains("Be terse.")
                    .contains("128");
        });
    }

    @Test
    void theSdksStreamParserReassemblesTheStreamedAnswer() {
        StubRule rule = new StubRule();
        rule.setName("long-answer");
        rule.setResponseText("alpha beta gamma delta epsilon zeta eta");
        stubs.save(rule);

        List<GenerateContentResponse> events = new ArrayList<>();
        try (Client client = client();
             ResponseStream<GenerateContentResponse> stream =
                     client.models.generateContentStream("gemini-2.5-flash", "go", null)) {
            stream.forEach(events::add);
        }

        assertThat(events).hasSizeGreaterThan(1);
        StringBuilder assembled = new StringBuilder();
        for (GenerateContentResponse event : events) {
            String text = event.text();
            if (text != null) {
                assembled.append(text);
            }
        }
        assertThat(assembled.toString()).isEqualTo("alpha beta gamma delta epsilon zeta eta");
    }

    @Test
    void theSdkSurfacesAFunctionCall() {
        StubRule rule = new StubRule();
        rule.setName("weather-tool");
        rule.setResponseText("");
        rule.setToolName("get_weather");
        rule.setToolArguments("{\"city\":\"Tokyo\"}");
        stubs.save(rule);

        try (Client client = client()) {
            GenerateContentResponse response =
                    client.models.generateContent("gemini-2.5-pro", "weather?", null);

            var calls = response.functionCalls();
            assertThat(calls).isNotNull().hasSize(1);
            assertThat(calls.get(0).name()).hasValue("get_weather");
            assertThat(calls.get(0).args().orElseThrow()).containsEntry("city", "Tokyo");
        }
    }

    @Test
    void theSdkParsesTheCountTokensResponse() {
        try (Client client = client()) {
            var response = client.models.countTokens("gemini-2.5-pro", "Hello there", null);

            assertThat(response.totalTokens().orElseThrow()).isPositive();
        }
    }

    @Test
    void theSdkParsesAnEmbedding() {
        try (Client client = client()) {
            var response = client.models.embedContent("text-embedding-004", "hello", null);

            assertThat(response.embeddings()).isPresent();
            // 4 dimensions come from the test profile.
            assertThat(response.embeddings().get().get(0).values().orElseThrow()).hasSize(4);
        }
    }

    @Test
    void theSdkRaisesOnASimulatedFailure() {
        StubRule rule = new StubRule();
        rule.setName("throttle");
        rule.setHttpStatus(429);
        rule.setErrorMessage("Quota exceeded");
        stubs.save(rule);

        try (Client client = client()) {
            assertThatThrownBy(() -> client.models.generateContent("gemini-2.5-pro", "hi", null))
                    .hasMessageContaining("Quota exceeded");
        }
    }
}
