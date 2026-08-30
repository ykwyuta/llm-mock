package com.github.llmmock.provider.bedrock;

import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.github.llmmock.support.EventStreamDecoder;
import com.github.llmmock.support.MockServerTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BedrockApiTest extends MockServerTest {

    private static final String CLAUDE = "anthropic.claude-sonnet-4-5-20250929-v1:0";

    @Test
    void converseMatchesTheDocumentedShape() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/converse", CLAUDE).contentType("application/json")
                        .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/...")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"Hello"}]}],
                                 "system":[{"text":"Be brief."}],
                                 "inferenceConfig":{"maxTokens":512,"temperature":0.5}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.message.role").value("assistant"))
                .andExpect(jsonPath("$.output.message.content[0].text")
                        .value("[llm-mock] echo: Hello"))
                .andExpect(jsonPath("$.stopReason").value("end_turn"))
                .andExpect(jsonPath("$.usage.inputTokens").isNumber())
                .andExpect(jsonPath("$.usage.outputTokens").isNumber())
                .andExpect(jsonPath("$.usage.totalTokens").isNumber())
                .andExpect(jsonPath("$.metrics.latencyMs").isNumber());
    }

    @Test
    void converseToolUseCarriesAToolUseIdAndObjectInput() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/converse", CLAUDE).contentType("application/json")
                        .header("X-Mock-Text", "")
                        .header("X-Mock-Tool-Name", "get_weather")
                        .header("X-Mock-Tool-Arguments", "{\"city\":\"Osaka\"}")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"weather?"}]}],
                                 "toolConfig":{"tools":[{"toolSpec":{"name":"get_weather",
                                   "description":"look it up",
                                   "inputSchema":{"json":{"type":"object"}}}}]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopReason").value("tool_use"))
                .andExpect(jsonPath("$.output.message.content[0].toolUse.name").value("get_weather"))
                .andExpect(jsonPath("$.output.message.content[0].toolUse.toolUseId")
                        .value(org.hamcrest.Matchers.startsWith("tooluse_")))
                .andExpect(jsonPath("$.output.message.content[0].toolUse.input.city").value("Osaka"));
    }

    @Test
    void converseStreamUsesTheAwsEventStreamFraming() throws Exception {
        byte[] body = mvc.perform(post("/bedrock/model/{id}/converse-stream", CLAUDE)
                        .contentType("application/json")
                        .header("X-Mock-Text", "one two three four five six seven")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"go"}]}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.amazon.eventstream"))
                .andReturn().getResponse().getContentAsByteArray();

        // The decoder verifies both CRC32s, so an SDK-incompatible frame fails here.
        List<EventStreamDecoder.Frame> frames = EventStreamDecoder.decode(body);
        assertThat(frames).extracting(EventStreamDecoder.Frame::eventType).containsExactly(
                "messageStart", "contentBlockStart", "contentBlockDelta", "contentBlockDelta",
                "contentBlockDelta", "contentBlockStop", "messageStop", "metadata");

        StringBuilder assembled = new StringBuilder();
        for (EventStreamDecoder.Frame frame : frames) {
            if ("contentBlockDelta".equals(frame.eventType())) {
                assembled.append(json.readTree(frame.payload()).get("delta").get("text").asString());
            }
        }
        assertThat(assembled.toString()).isEqualTo("one two three four five six seven");

        assertThat(json.readTree(frames.get(0).payload()).get("role").asString())
                .isEqualTo("assistant");
        assertThat(json.readTree(frames.get(6).payload()).get("stopReason").asString())
                .isEqualTo("end_turn");
        assertThat(json.readTree(frames.get(7).payload()).get("usage").get("totalTokens").asInt())
                .isPositive();
    }

    @Test
    void converseStreamToolUseOpensWithAToolUseStart() throws Exception {
        byte[] body = mvc.perform(post("/bedrock/model/{id}/converse-stream", CLAUDE)
                        .contentType("application/json")
                        .header("X-Mock-Text", "")
                        .header("X-Mock-Tool-Name", "get_weather")
                        .header("X-Mock-Tool-Arguments", "{\"city\":\"Nara\"}")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"go"}]}]}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        List<EventStreamDecoder.Frame> frames = EventStreamDecoder.decode(body);
        var start = json.readTree(frames.get(1).payload());
        assertThat(start.get("start").get("toolUse").get("name").asString()).isEqualTo("get_weather");

        var delta = json.readTree(frames.get(2).payload());
        // Bedrock streams tool input as partial JSON text, not as an object.
        assertThat(delta.get("delta").get("toolUse").get("input").asString())
                .isEqualTo("{\"city\":\"Nara\"}");
    }

    @Test
    void invokeModelUsesTheAnthropicNativeBodyForClaudeModels() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/invoke", CLAUDE).contentType("application/json")
                        .content("""
                                {"anthropic_version":"bedrock-2023-05-31","max_tokens":256,
                                 "messages":[{"role":"user","content":"Hello"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content[0].type").value("text"))
                .andExpect(jsonPath("$.content[0].text").value("[llm-mock] echo: Hello"))
                .andExpect(jsonPath("$.stop_reason").value("end_turn"))
                .andExpect(jsonPath("$.usage.input_tokens").isNumber());
    }

    @Test
    void invokeModelUsesTheTitanNativeBodyForTitanModels() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/invoke", "amazon.titan-text-express-v1")
                        .contentType("application/json")
                        .content("""
                                {"inputText":"Hello Titan",
                                 "textGenerationConfig":{"maxTokenCount":128,"temperature":0.3}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTextTokenCount").isNumber())
                .andExpect(jsonPath("$.results[0].outputText").value("[llm-mock] echo: Hello Titan"))
                .andExpect(jsonPath("$.results[0].completionReason").value("FINISH"))
                .andExpect(jsonPath("$.results[0].tokenCount").isNumber());
    }

    @Test
    void invokeModelUsesTheLlamaNativeBodyForLlamaModels() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/invoke", "meta.llama3-70b-instruct-v1:0")
                        .contentType("application/json")
                        .content("""
                                {"prompt":"Hello Llama","max_gen_len":128}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value("[llm-mock] echo: Hello Llama"))
                .andExpect(jsonPath("$.stop_reason").value("stop"))
                .andExpect(jsonPath("$.prompt_token_count").isNumber());
    }

    @Test
    void invokeModelUsesTheConverseShapedBodyForNovaModels() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/invoke", "amazon.nova-pro-v1:0")
                        .contentType("application/json")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"Hello Nova"}]}],
                                 "inferenceConfig":{"maxTokens":128}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.message.content[0].text")
                        .value("[llm-mock] echo: Hello Nova"))
                .andExpect(jsonPath("$.stopReason").value("end_turn"))
                .andExpect(jsonPath("$.usage.totalTokens").isNumber());
    }

    @Test
    void invokeWithResponseStreamWrapsNativeChunksAsBase64Bytes() throws Exception {
        byte[] body = mvc.perform(post("/bedrock/model/{id}/invoke-with-response-stream", CLAUDE)
                        .contentType("application/json")
                        .header("X-Mock-Text", "one two three four")
                        .content("""
                                {"anthropic_version":"bedrock-2023-05-31","max_tokens":128,
                                 "messages":[{"role":"user","content":"go"}]}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.amazon.eventstream"))
                .andReturn().getResponse().getContentAsByteArray();

        List<EventStreamDecoder.Frame> frames = EventStreamDecoder.decode(body);
        assertThat(frames).allSatisfy(frame ->
                assertThat(frame.eventType()).isEqualTo("chunk"));

        StringBuilder assembled = new StringBuilder();
        for (EventStreamDecoder.Frame frame : frames) {
            String encoded = json.readTree(frame.payload()).get("bytes").asString();
            var event = json.readTree(new String(Base64.getDecoder().decode(encoded),
                    StandardCharsets.UTF_8));
            if ("content_block_delta".equals(event.get("type").asString())) {
                assembled.append(event.get("delta").get("text").asString());
            }
        }
        assertThat(assembled.toString()).isEqualTo("one two three four");
    }

    @Test
    void errorsCarryTheAwsErrorTypeHeader() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/converse", CLAUDE).contentType("application/json")
                        .header("X-Mock-Status", "429")
                        .header("X-Mock-Error-Message", "Too many requests")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"hi"}]}]}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("x-amzn-ErrorType", "ThrottlingException"))
                .andExpect(header().exists("x-amzn-RequestId"))
                .andExpect(jsonPath("$.message").value("Too many requests"));
    }

    @Test
    void anEmptyMessageListIsAValidationException() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/converse", CLAUDE).contentType("application/json")
                        .content("""
                                {"messages":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("x-amzn-ErrorType", "ValidationException"));
    }

    @Test
    void theModelIdIsRecordedExactlyAsSentIncludingItsColonSuffix() throws Exception {
        mvc.perform(post("/bedrock/model/{id}/converse", CLAUDE).contentType("application/json")
                        .content("""
                                {"messages":[{"role":"user","content":[{"text":"hi"}]}]}"""))
                .andExpect(status().isOk());

        assertThat(logs.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getModel()).isEqualTo(CLAUDE);
            assertThat(entry.getEndpoint()).isEqualTo("converse");
        });
    }
}
