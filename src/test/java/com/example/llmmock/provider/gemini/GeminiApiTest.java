package com.example.llmmock.provider.gemini;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.llmmock.support.MockServerTest;
import com.example.llmmock.support.Sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GeminiApiTest extends MockServerTest {

    private static final String GENERATE = "/gemini/v1beta/models/gemini-2.5-pro:generateContent";

    @Test
    void generateContentMatchesTheDocumentedShape() throws Exception {
        mvc.perform(post(GENERATE).contentType("application/json")
                        .header("x-goog-api-key", "test-key")
                        .content("""
                                {"contents":[{"role":"user","parts":[{"text":"Hello"}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].content.role").value("model"))
                .andExpect(jsonPath("$.candidates[0].content.parts[0].text")
                        .value("[llm-mock] echo: Hello"))
                .andExpect(jsonPath("$.candidates[0].finishReason").value("STOP"))
                .andExpect(jsonPath("$.candidates[0].index").value(0))
                .andExpect(jsonPath("$.candidates[0].safetyRatings[0].category")
                        .value("HARM_CATEGORY_HARASSMENT"))
                .andExpect(jsonPath("$.usageMetadata.promptTokenCount").isNumber())
                .andExpect(jsonPath("$.usageMetadata.candidatesTokenCount").isNumber())
                .andExpect(jsonPath("$.usageMetadata.totalTokenCount").isNumber())
                .andExpect(jsonPath("$.modelVersion").value("gemini-2.5-pro"))
                .andExpect(jsonPath("$.responseId").exists());
    }

    @Test
    void systemInstructionAndGenerationConfigAreAccepted() throws Exception {
        mvc.perform(post(GENERATE).contentType("application/json").content("""
                        {"systemInstruction":{"parts":[{"text":"Answer in haiku."}]},
                         "contents":[{"role":"user","parts":[{"text":"the sea"}]}],
                         "generationConfig":{"temperature":0.2,"maxOutputTokens":64,
                           "stopSequences":["END"]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].content.parts[0].text")
                        .value("[llm-mock] echo: the sea"));

        assertThat(logs.findAll()).singleElement().satisfies(entry ->
                assertThat(entry.getRequestBody()).contains("Answer in haiku."));
    }

    @Test
    void snakeCaseFieldNamesAreAcceptedAlongsideCamelCase() throws Exception {
        mvc.perform(post(GENERATE).contentType("application/json").content("""
                        {"system_instruction":{"parts":[{"text":"Be brief."}]},
                         "contents":[{"role":"user","parts":[{"text":"hi"}]}],
                         "generation_config":{"max_output_tokens":32}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].content.parts[0].text")
                        .value("[llm-mock] echo: hi"));
    }

    @Test
    void functionCallsAppearAsAFunctionCallPart() throws Exception {
        mvc.perform(post(GENERATE).contentType("application/json")
                        .header("X-Mock-Text", "")
                        .header("X-Mock-Tool-Name", "get_weather")
                        .header("X-Mock-Tool-Arguments", "{\"city\":\"Kyoto\"}")
                        .content("""
                                {"contents":[{"role":"user","parts":[{"text":"weather?"}]}],
                                 "tools":[{"functionDeclarations":[{"name":"get_weather",
                                   "description":"look it up","parameters":{"type":"object"}}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].content.parts[0].functionCall.name")
                        .value("get_weather"))
                .andExpect(jsonPath("$.candidates[0].content.parts[0].functionCall.args.city")
                        .value("Kyoto"))
                // Gemini has no separate tool stop reason.
                .andExpect(jsonPath("$.candidates[0].finishReason").value("STOP"));
    }

    @Test
    void streamGenerateContentWithAltSseEmitsIncrementalEvents() throws Exception {
        String body = mvc.perform(post(
                        "/gemini/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse")
                        .contentType("application/json")
                        .header("X-Mock-Text", "one two three four five six seven")
                        .content("""
                                {"contents":[{"role":"user","parts":[{"text":"go"}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn().getResponse().getContentAsString();

        List<String> payloads = Sse.dataLines(body);
        assertThat(payloads).hasSize(3);

        StringBuilder assembled = new StringBuilder();
        for (String payload : payloads) {
            assembled.append(json.readTree(payload)
                    .get("candidates").get(0).get("content").get("parts").get(0).get("text").asString());
        }
        assertThat(assembled.toString()).isEqualTo("one two three four five six seven");

        // Only the final event carries the stop reason and the usage totals.
        var last = json.readTree(payloads.get(payloads.size() - 1));
        assertThat(last.get("candidates").get(0).get("finishReason").asString()).isEqualTo("STOP");
        assertThat(last.get("usageMetadata").get("totalTokenCount").asInt()).isPositive();
        assertThat(json.readTree(payloads.get(0)).get("usageMetadata")).isNull();
    }

    @Test
    void streamGenerateContentWithoutAltSseReturnsAJsonArray() throws Exception {
        String body = mvc.perform(post(
                        "/gemini/v1beta/models/gemini-2.5-flash:streamGenerateContent")
                        .contentType("application/json")
                        .header("X-Mock-Text", "one two three four")
                        .content("""
                                {"contents":[{"role":"user","parts":[{"text":"go"}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();

        var array = json.readTree(body);
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(0).get("candidates").get(0).get("content").get("parts").get(0)
                .get("text").asString()).isEqualTo("one two three ");
    }

    @Test
    void errorsUseTheGoogleApiEnvelope() throws Exception {
        mvc.perform(post(GENERATE).contentType("application/json")
                        .header("X-Mock-Status", "429")
                        .header("X-Mock-Error-Message", "Quota exceeded")
                        .content("""
                                {"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value(429))
                .andExpect(jsonPath("$.error.status").value("RESOURCE_EXHAUSTED"))
                .andExpect(jsonPath("$.error.message").value("Quota exceeded"));
    }

    @Test
    void emptyContentsAreRejected() throws Exception {
        mvc.perform(post(GENERATE).contentType("application/json").content("""
                        {"contents":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.status").value("INVALID_ARGUMENT"));
    }

    @Test
    void countTokensIsSupported() throws Exception {
        mvc.perform(post("/gemini/v1beta/models/gemini-2.5-pro:countTokens")
                        .contentType("application/json").content("""
                                {"contents":[{"role":"user","parts":[{"text":"Hello there"}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokens").isNumber());
    }

    @Test
    void embedContentReturnsADeterministicVectorOfTheConfiguredSize() throws Exception {
        String first = mvc.perform(post("/gemini/v1beta/models/text-embedding-004:embedContent")
                        .contentType("application/json").content("""
                                {"model":"models/text-embedding-004",
                                 "content":{"parts":[{"text":"hello"}]}}"""))
                .andExpect(status().isOk())
                // 4 dimensions come from the test profile.
                .andExpect(jsonPath("$.embedding.values.length()").value(4))
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/gemini/v1beta/models/text-embedding-004:embedContent")
                        .contentType("application/json").content("""
                                {"model":"models/text-embedding-004",
                                 "content":{"parts":[{"text":"hello"}]}}"""))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void modelsAreListedWithTheModelsPrefix() throws Exception {
        mvc.perform(get("/gemini/v1beta/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].name").value("models/gemini-2.5-pro"))
                .andExpect(jsonPath("$.models[0].supportedGenerationMethods[0]")
                        .value("generateContent"));

        mvc.perform(get("/gemini/v1beta/models/gemini-2.5-pro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("models/gemini-2.5-pro"));

        mvc.perform(get("/gemini/v1beta/models/not-a-model"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.status").value("NOT_FOUND"));
    }
}
