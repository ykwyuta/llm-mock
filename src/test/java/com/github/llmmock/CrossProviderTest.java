package com.github.llmmock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.llmmock.core.Provider;
import com.github.llmmock.store.StubRule;
import com.github.llmmock.support.MockServerTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The point of the design: one rule, expressed once, answers on all four protocols, and one
 * request log answers "what did the application actually send" regardless of which SDK sent
 * it. A suite that migrates a service from one provider to another keeps its stubs.
 */
class CrossProviderTest extends MockServerTest {

    private void registerSharedStub() {
        StubRule rule = new StubRule();
        rule.setName("shared-weather");
        rule.setProvider(Provider.ANY);
        rule.setPromptPattern("(?i)weather");
        rule.setResponseText("It is sunny.");
        stubs.save(rule);
    }

    @Test
    void oneProviderAgnosticStubAnswersOnEveryProtocol() throws Exception {
        registerSharedStub();

        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                        .content("""
                                {"model":"gpt-4o",
                                 "messages":[{"role":"user","content":"how is the weather?"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("It is sunny."));

        mvc.perform(post("/anthropic/v1/messages").contentType("application/json")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":64,
                                 "messages":[{"role":"user","content":"how is the weather?"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].text").value("It is sunny."));

        mvc.perform(post("/gemini/v1beta/models/gemini-2.5-pro:generateContent")
                        .contentType("application/json")
                        .content("""
                                {"contents":[{"role":"user",
                                  "parts":[{"text":"how is the weather?"}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].content.parts[0].text").value("It is sunny."));

        mvc.perform(post("/bedrock/model/{id}/converse", "anthropic.claude-sonnet-4-5-20250929-v1:0")
                        .contentType("application/json")
                        .content("""
                                {"messages":[{"role":"user",
                                  "content":[{"text":"how is the weather?"}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.message.content[0].text").value("It is sunny."));

        assertThat(logs.findAll()).hasSize(4)
                .allSatisfy(entry -> assertThat(entry.getMatchedStub()).isEqualTo("shared-weather"))
                .extracting(entry -> entry.getProvider())
                .containsExactlyInAnyOrder(Provider.OPENAI, Provider.ANTHROPIC, Provider.GEMINI,
                        Provider.BEDROCK);
    }

    @Test
    void oneErrorStubMakesEveryProtocolFailInItsOwnErrorFormat() throws Exception {
        StubRule rule = new StubRule();
        rule.setName("shared-throttle");
        rule.setProvider(Provider.ANY);
        rule.setHttpStatus(429);
        rule.setErrorMessage("Too many requests");
        stubs.save(rule);

        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"));

        mvc.perform(post("/anthropic/v1/messages").contentType("application/json")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":64,
                                 "messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"))
                .andExpect(jsonPath("$.type").value("error"));

        mvc.perform(post("/gemini/v1beta/models/gemini-2.5-pro:generateContent")
                        .contentType("application/json")
                        .content("""
                                {"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.status").value("RESOURCE_EXHAUSTED"));

        mvc.perform(post("/bedrock/model/{id}/converse", "amazon.nova-pro-v1:0")
                        .contentType("application/json")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"hi"}]}]}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests"));

        // Failures are recorded too, which is what makes them assertable after the fact.
        assertThat(logs.findAll()).hasSize(4)
                .allSatisfy(entry -> assertThat(entry.getHttpStatus()).isEqualTo(429));
    }

    @Test
    void aProviderScopedStubDoesNotLeakIntoTheOtherProtocols() throws Exception {
        StubRule rule = new StubRule();
        rule.setName("openai-only");
        rule.setProvider(Provider.OPENAI);
        rule.setResponseText("OpenAI answer");
        stubs.save(rule);

        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(jsonPath("$.choices[0].message.content").value("OpenAI answer"));

        mvc.perform(post("/gemini/v1beta/models/gemini-2.5-pro:generateContent")
                        .contentType("application/json")
                        .content("""
                                {"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""))
                .andExpect(jsonPath("$.candidates[0].content.parts[0].text")
                        .value("[llm-mock] echo: hi"));
    }

    @Test
    void theSameToolCallStubProducesEachProtocolsOwnToolShape() throws Exception {
        StubRule rule = new StubRule();
        rule.setName("shared-tool");
        rule.setProvider(Provider.ANY);
        rule.setResponseText("");
        rule.setToolName("get_weather");
        rule.setToolArguments("{\"city\":\"Tokyo\"}");
        stubs.save(rule);

        // OpenAI: arguments are a JSON string on a function call.
        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.arguments")
                        .value("{\"city\":\"Tokyo\"}"));

        // Anthropic: input is a JSON object on a tool_use block.
        mvc.perform(post("/anthropic/v1/messages").contentType("application/json")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":64,
                                 "messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(jsonPath("$.content[0].input.city").value("Tokyo"));

        // Gemini: args is a JSON object on a functionCall part.
        mvc.perform(post("/gemini/v1beta/models/gemini-2.5-pro:generateContent")
                        .contentType("application/json")
                        .content("""
                                {"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""))
                .andExpect(jsonPath("$.candidates[0].content.parts[0].functionCall.args.city")
                        .value("Tokyo"));

        // Bedrock: input is a JSON object on a toolUse block.
        mvc.perform(post("/bedrock/model/{id}/converse", "anthropic.claude-sonnet-4-5-20250929-v1:0")
                        .contentType("application/json")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"hi"}]}]}"""))
                .andExpect(jsonPath("$.output.message.content[0].toolUse.input.city").value("Tokyo"));
    }

    @Test
    void everyProtocolIsMountedUnderItsOwnPrefixSoTheirPathsCannotCollide() throws Exception {
        // Both OpenAI and Anthropic serve GET /v1/models with entirely different payloads.
        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isOk());

        // The same path without a provider prefix is not mapped at all.
        mvc.perform(post("/v1/chat/completions").contentType("application/json")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void limitedUseStubsLetOneSuiteScriptARetrySequence() throws Exception {
        StubRule failFirst = new StubRule();
        failFirst.setName("fail-once");
        failFirst.setProvider(Provider.ANY);
        failFirst.setPriority(10);
        failFirst.setHttpStatus(503);
        failFirst.setErrorMessage("temporarily unavailable");
        failFirst.setRemainingUses(1);
        stubs.save(failFirst);

        StubRule thenSucceed = new StubRule();
        thenSucceed.setName("then-ok");
        thenSucceed.setProvider(Provider.ANY);
        thenSucceed.setResponseText("recovered");
        stubs.save(thenSucceed);

        String body = """
                {"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}""";

        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json").content(body))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("recovered"));

        assertThat(logs.findAll()).extracting(entry -> entry.getHttpStatus())
                .isEqualTo(List.of(503, 200));
    }
}
