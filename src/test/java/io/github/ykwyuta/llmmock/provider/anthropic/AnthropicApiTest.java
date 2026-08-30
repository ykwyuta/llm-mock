package io.github.ykwyuta.llmmock.provider.anthropic;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ykwyuta.llmmock.support.MockServerTest;
import io.github.ykwyuta.llmmock.support.Sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnthropicApiTest extends MockServerTest {

    private static final String MESSAGES = "/anthropic/v1/messages";

    @Test
    void messageResponseMatchesTheDocumentedShape() throws Exception {
        mvc.perform(post(MESSAGES).contentType("application/json")
                        .header("x-api-key", "sk-ant-test")
                        .header("anthropic-version", "2023-06-01")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":1024,
                                 "system":"Be concise.",
                                 "messages":[{"role":"user","content":"Hello"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("msg_")))
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-4-5"))
                .andExpect(jsonPath("$.content[0].type").value("text"))
                .andExpect(jsonPath("$.content[0].text").value("[llm-mock] echo: Hello"))
                .andExpect(jsonPath("$.stop_reason").value("end_turn"))
                // stop_sequence is documented as always present, null when unused.
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("\"stop_sequence\":null")))
                .andExpect(jsonPath("$.usage.input_tokens").isNumber())
                .andExpect(jsonPath("$.usage.output_tokens").isNumber());
    }

    @Test
    void maxTokensIsRequiredJustAsOnTheRealApi() throws Exception {
        mvc.perform(post(MESSAGES).contentType("application/json")
                        .content("""
                                {"model":"claude-sonnet-4-5",
                                 "messages":[{"role":"user","content":"Hello"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("max_tokens")));
    }

    @Test
    void systemPromptsAndContentBlockArraysBothFeedTheMatcher() throws Exception {
        mvc.perform(post(MESSAGES).contentType("application/json")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":64,
                                 "system":[{"type":"text","text":"You are a pirate."}],
                                 "messages":[{"role":"user","content":[
                                   {"type":"text","text":"Where is the treasure?"}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].text")
                        .value("[llm-mock] echo: Where is the treasure?"));

        assertThat(logs.findAll()).singleElement().satisfies(entry ->
                assertThat(entry.getRequestBody()).contains("You are a pirate."));
    }

    @Test
    void toolUseBlocksCarryInputAsAJsonObject() throws Exception {
        mvc.perform(post(MESSAGES).contentType("application/json")
                        .header("X-Mock-Tool-Name", "get_weather")
                        .header("X-Mock-Tool-Arguments", "{\"city\":\"Tokyo\"}")
                        // An empty X-Mock-Text asks for a tool call with no accompanying prose.
                        .header("X-Mock-Text", "")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":64,
                                 "messages":[{"role":"user","content":"weather?"}],
                                 "tools":[{"name":"get_weather","description":"Look up weather",
                                   "input_schema":{"type":"object"}}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stop_reason").value("tool_use"))
                .andExpect(jsonPath("$.content[0].type").value("tool_use"))
                .andExpect(jsonPath("$.content[0].id")
                        .value(org.hamcrest.Matchers.startsWith("toolu_")))
                .andExpect(jsonPath("$.content[0].name").value("get_weather"))
                // An object here, not the JSON string that OpenAI uses.
                .andExpect(jsonPath("$.content[0].input.city").value("Tokyo"));
    }

    @Test
    void streamingEmitsTheDocumentedNamedEventSequence() throws Exception {
        String body = mvc.perform(post(MESSAGES).contentType("application/json")
                        .header("X-Mock-Text", "alpha beta gamma delta")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":64,"stream":true,
                                 "messages":[{"role":"user","content":"go"}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn().getResponse().getContentAsString();

        List<Sse.Event> events = Sse.parse(body);
        assertThat(events).extracting(Sse.Event::name).containsExactly(
                "message_start", "ping", "content_block_start", "content_block_delta",
                "content_block_delta", "content_block_stop", "message_delta", "message_stop");

        // Every event repeats its own name in the payload's "type" field.
        for (Sse.Event event : events) {
            assertThat(json.readTree(event.data()).get("type").asString()).isEqualTo(event.name());
        }

        StringBuilder assembled = new StringBuilder();
        for (Sse.Event event : events) {
            if ("content_block_delta".equals(event.name())) {
                assembled.append(json.readTree(event.data()).get("delta").get("text").asString());
            }
        }
        assertThat(assembled.toString()).isEqualTo("alpha beta gamma delta");

        var messageDelta = json.readTree(events.get(6).data());
        assertThat(messageDelta.get("delta").get("stop_reason").asString()).isEqualTo("end_turn");
        assertThat(messageDelta.get("usage").get("output_tokens").asInt()).isPositive();
    }

    @Test
    void streamedToolUseArrivesAsInputJsonDelta() throws Exception {
        String body = mvc.perform(post(MESSAGES).contentType("application/json")
                        .header("X-Mock-Text", "")
                        .header("X-Mock-Tool-Name", "get_weather")
                        .header("X-Mock-Tool-Arguments", "{\"city\":\"Tokyo\"}")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":64,"stream":true,
                                 "messages":[{"role":"user","content":"go"}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Sse.Event> events = Sse.parse(body);
        Sse.Event start = events.stream()
                .filter(e -> "content_block_start".equals(e.name())).findFirst().orElseThrow();
        assertThat(json.readTree(start.data()).get("content_block").get("type").asString())
                .isEqualTo("tool_use");

        Sse.Event delta = events.stream()
                .filter(e -> "content_block_delta".equals(e.name())).findFirst().orElseThrow();
        var deltaNode = json.readTree(delta.data()).get("delta");
        assertThat(deltaNode.get("type").asString()).isEqualTo("input_json_delta");
        assertThat(deltaNode.get("partial_json").asString()).isEqualTo("{\"city\":\"Tokyo\"}");
    }

    @Test
    void simulatedOverloadUsesTheAnthropicErrorEnvelope() throws Exception {
        mvc.perform(post(MESSAGES).contentType("application/json")
                        .header("X-Mock-Status", "529")
                        .content("""
                                {"model":"claude-sonnet-4-5","max_tokens":64,
                                 "messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().is(529))
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("overloaded_error"))
                .andExpect(jsonPath("$.request_id")
                        .value(org.hamcrest.Matchers.startsWith("req_")));
    }

    @Test
    void countTokensReturnsAnInputTokenCount() throws Exception {
        mvc.perform(post("/anthropic/v1/messages/count_tokens").contentType("application/json")
                        .content("""
                                {"model":"claude-sonnet-4-5",
                                 "messages":[{"role":"user","content":"Hello there"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input_tokens").isNumber());

        assertThat(logs.findAll()).singleElement().satisfies(entry ->
                assertThat(entry.getEndpoint()).isEqualTo("messages.count_tokens"));
    }

    @Test
    void modelsAreListedInAnthropicsPaginatedShape() throws Exception {
        mvc.perform(get("/anthropic/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("model"))
                .andExpect(jsonPath("$.data[0].id").value("claude-opus-4-5"))
                .andExpect(jsonPath("$.data[0].display_name").exists())
                .andExpect(jsonPath("$.has_more").value(false));
    }
}
