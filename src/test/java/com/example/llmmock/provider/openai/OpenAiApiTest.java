package com.example.llmmock.provider.openai;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.llmmock.core.Provider;
import com.example.llmmock.store.StubRule;
import com.example.llmmock.support.MockServerTest;
import com.example.llmmock.support.Sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenAiApiTest extends MockServerTest {

    private static final String CHAT = "/openai/v1/chat/completions";

    @Test
    void chatCompletionMatchesTheDocumentedResponseShape() throws Exception {
        mvc.perform(post(CHAT).contentType("application/json")
                        .header("Authorization", "Bearer sk-test")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"Hello"}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("chatcmpl-")))
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.created").isNumber())
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.choices[0].index").value(0))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("[llm-mock] echo: Hello"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                // Documented as always present, so the key must be there holding null.
                // jsonPath cannot tell "null" from "absent", so assert on the raw body.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"logprobs\":null")))
                .andExpect(jsonPath("$.usage.prompt_tokens").isNumber())
                .andExpect(jsonPath("$.usage.completion_tokens").isNumber())
                .andExpect(jsonPath("$.usage.total_tokens").isNumber());
    }

    @Test
    void contentPartArraysAreFlattenedIntoThePrompt() throws Exception {
        mvc.perform(post(CHAT).contentType("application/json").content("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":[
                          {"type":"text","text":"describe this"},
                          {"type":"image_url","image_url":{"url":"https://example.test/a.png"}}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content")
                        .value("[llm-mock] echo: describe this"));
    }

    @Test
    void aStubDrivesTheAnswerAndIsReportedInTheRequestLog() throws Exception {
        StubRule rule = new StubRule();
        rule.setName("weather");
        rule.setProvider(Provider.OPENAI);
        rule.setPromptPattern("(?i)weather");
        rule.setResponseText("It is sunny in Tokyo.");
        stubs.save(rule);

        mvc.perform(post(CHAT).contentType("application/json").content("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"How is the weather?"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("It is sunny in Tokyo."));

        assertThat(logs.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getMatchedStub()).isEqualTo("weather");
            assertThat(entry.getEndpoint()).isEqualTo("chat.completions");
            assertThat(entry.getRequestBody()).contains("How is the weather?");
        });
    }

    @Test
    void toolCallsAreRenderedAsTheFunctionCallShape() throws Exception {
        mvc.perform(post(CHAT).contentType("application/json")
                        .header("X-Mock-Tool-Name", "get_weather")
                        .header("X-Mock-Tool-Arguments", "{\"city\":\"Tokyo\"}")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"weather?"}],
                                 "tools":[{"type":"function","function":{"name":"get_weather",
                                   "parameters":{"type":"object"}}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].type").value("function"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].id")
                        .value(org.hamcrest.Matchers.startsWith("call_")))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name")
                        .value("get_weather"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.arguments")
                        .value("{\"city\":\"Tokyo\"}"));
    }

    @Test
    void streamingEmitsChunksThatReassembleIntoTheFullAnswerAndEndWithDone() throws Exception {
        String body = mvc.perform(post(CHAT).contentType("application/json")
                        .header("X-Mock-Text", "one two three four five six seven")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"go"}],
                                 "stream":true,"stream_options":{"include_usage":true}}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn().getResponse().getContentAsString();

        List<String> payloads = Sse.dataLines(body);
        assertThat(payloads).last().isEqualTo("[DONE]");

        StringBuilder assembled = new StringBuilder();
        String finishReason = null;
        Integer totalTokens = null;
        for (String payload : payloads.subList(0, payloads.size() - 1)) {
            var chunk = json.readTree(payload);
            assertThat(chunk.get("object").asString()).isEqualTo("chat.completion.chunk");
            var choices = chunk.get("choices");
            if (choices != null && !choices.isEmpty()) {
                var delta = choices.get(0).get("delta");
                if (delta.get("content") != null && !delta.get("content").isNull()) {
                    assembled.append(delta.get("content").asString());
                }
                var reason = choices.get(0).get("finish_reason");
                if (reason != null && !reason.isNull()) {
                    finishReason = reason.asString();
                }
            }
            if (chunk.get("usage") != null && !chunk.get("usage").isNull()) {
                totalTokens = chunk.get("usage").get("total_tokens").asInt();
            }
        }

        assertThat(assembled.toString()).isEqualTo("one two three four five six seven");
        assertThat(finishReason).isEqualTo("stop");
        assertThat(totalTokens).isNotNull().isPositive();
    }

    @Test
    void usageIsOmittedFromTheStreamUnlessRequested() throws Exception {
        String body = mvc.perform(post(CHAT).contentType("application/json").content("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"go"}],"stream":true}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"usage\"");
    }

    @Test
    void simulatedFailuresUseTheOpenAiErrorEnvelope() throws Exception {
        mvc.perform(post(CHAT).contentType("application/json")
                        .header("X-Mock-Status", "429")
                        .header("X-Mock-Error-Message", "Rate limit reached")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"))
                .andExpect(jsonPath("$.error.message").value("Rate limit reached"));
    }

    @Test
    void aMissingModelIsRejectedBeforeAnythingElse() throws Exception {
        mvc.perform(post(CHAT).contentType("application/json")
                        .content("""
                                {"messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void anEmptyMessageListIsRejected() throws Exception {
        mvc.perform(post(CHAT).contentType("application/json")
                        .content("""
                                {"model":"gpt-4o","messages":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("messages")));
    }

    @Test
    void legacyCompletionsAreSupported() throws Exception {
        mvc.perform(post("/openai/v1/completions").contentType("application/json")
                        .content("""
                                {"model":"gpt-4o","prompt":"Once upon a time","max_tokens":20}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("text_completion"))
                .andExpect(jsonPath("$.choices[0].text").value("[llm-mock] echo: Once upon a time"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"));
    }

    @Test
    void embeddingsAreDeterministicAndHonourTheRequestedDimension() throws Exception {
        String first = mvc.perform(post("/openai/v1/embeddings").contentType("application/json")
                        .content("""
                                {"model":"text-embedding-3-small","input":["a","b"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].object").value("embedding"))
                .andExpect(jsonPath("$.data[0].index").value(0))
                // 8 dimensions come from the test profile.
                .andExpect(jsonPath("$.data[0].embedding.length()").value(8))
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/openai/v1/embeddings").contentType("application/json")
                        .content("""
                                {"model":"text-embedding-3-small","input":["a","b"]}"""))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void embeddingsHonourAnExplicitDimensionsParameter() throws Exception {
        mvc.perform(post("/openai/v1/embeddings").contentType("application/json")
                        .content("""
                                {"model":"text-embedding-3-small","input":"a","dimensions":32}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].embedding.length()").value(32));
    }

    @Test
    void modelsAreListedAndLookedUpIndividually() throws Exception {
        mvc.perform(get("/openai/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].object").value("model"));

        mvc.perform(get("/openai/v1/models/gpt-4o"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("gpt-4o"));

        mvc.perform(get("/openai/v1/models/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found_error"));
    }
}
