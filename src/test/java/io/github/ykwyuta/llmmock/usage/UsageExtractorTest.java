package io.github.ykwyuta.llmmock.usage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.ykwyuta.llmmock.core.Provider;
import io.github.ykwyuta.llmmock.provider.bedrock.EventStreamEncoder;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;

/**
 * The token counts each provider reports, read back out of the response body. In proxy
 * mode this is the only place the numbers exist, so every documented shape is covered
 * explicitly - including the streaming ones, where the counts are split across events.
 */
class UsageExtractorTest {

    private final UsageExtractor extractor = new UsageExtractor(JsonMapper.builder().build());

    private Optional<UsageExtractor.Extracted> extract(Provider provider, String path,
                                                       String contentType, String body) {
        return extractor.extract(provider, path, contentType,
                body.getBytes(StandardCharsets.UTF_8));
    }

    // --- OpenAI ------------------------------------------------------------------------

    @Test
    void openAiChatCompletion() {
        var extracted = extract(Provider.OPENAI, "/v1/chat/completions", "application/json", """
                {"id":"chatcmpl-x","model":"gpt-4o","choices":[],
                 "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18,
                          "prompt_tokens_details":{"cached_tokens":4}}}""").orElseThrow();

        assertThat(extracted.model()).isEqualTo("gpt-4o");
        assertThat(extracted.usage().inputTokens()).isEqualTo(11);
        assertThat(extracted.usage().outputTokens()).isEqualTo(7);
        assertThat(extracted.usage().totalTokens()).isEqualTo(18);
        assertThat(extracted.usage().cacheReadTokens()).isEqualTo(4);
    }

    @Test
    void openAiStreamCarriesUsageOnlyInItsFinalChunk() {
        var extracted = extract(Provider.OPENAI, "/v1/chat/completions", "text/event-stream", """
                data: {"id":"c","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"hi"}}]}

                data: {"id":"c","object":"chat.completion.chunk","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}

                data: [DONE]

                """).orElseThrow();

        assertThat(extracted.model()).isEqualTo("gpt-4o");
        assertThat(extracted.usage().totalTokens()).isEqualTo(8);
    }

    @Test
    void openAiStreamWithoutIncludeUsageReportsNothingRatherThanZero() {
        // Silently recording zero would understate a real bill, so nothing is recorded.
        assertThat(extract(Provider.OPENAI, "/v1/chat/completions", "text/event-stream", """
                data: {"id":"c","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"hi"}}]}

                data: [DONE]

                """)).isEmpty();
    }

    // --- Anthropic ---------------------------------------------------------------------

    @Test
    void anthropicMessage() {
        var extracted = extract(Provider.ANTHROPIC, "/v1/messages", "application/json", """
                {"id":"msg_x","model":"claude-sonnet-4-5","content":[],
                 "usage":{"input_tokens":12,"output_tokens":9,
                          "cache_read_input_tokens":6,"cache_creation_input_tokens":2}}""")
                .orElseThrow();

        assertThat(extracted.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(extracted.usage().inputTokens()).isEqualTo(12);
        assertThat(extracted.usage().outputTokens()).isEqualTo(9);
        assertThat(extracted.usage().cacheReadTokens()).isEqualTo(6);
        assertThat(extracted.usage().cacheWriteTokens()).isEqualTo(2);
    }

    @Test
    void anthropicStreamSplitsTheCountsAcrossTwoEvents() {
        // input_tokens arrive in message_start, output_tokens only in message_delta.
        var extracted = extract(Provider.ANTHROPIC, "/v1/messages", "text/event-stream", """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_x","model":"claude-sonnet-4-5","usage":{"input_tokens":21,"output_tokens":0}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":13}}

                event: message_stop
                data: {"type":"message_stop"}

                """).orElseThrow();

        assertThat(extracted.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(extracted.usage().inputTokens()).isEqualTo(21);
        assertThat(extracted.usage().outputTokens()).isEqualTo(13);
        assertThat(extracted.usage().totalTokens()).isEqualTo(34);
    }

    @Test
    void countingTokensIsNotBilledAsACompletion() {
        assertThat(extract(Provider.ANTHROPIC, "/v1/messages/count_tokens", "application/json",
                """
                {"input_tokens":25}""")).isEmpty();
    }

    // --- Gemini ------------------------------------------------------------------------

    @Test
    void geminiGenerateContent() {
        var extracted = extract(Provider.GEMINI,
                "/v1beta/models/gemini-2.5-pro:generateContent", "application/json", """
                {"candidates":[],"modelVersion":"gemini-2.5-pro",
                 "usageMetadata":{"promptTokenCount":14,"candidatesTokenCount":8,
                                  "totalTokenCount":22,"cachedContentTokenCount":3}}""")
                .orElseThrow();

        assertThat(extracted.model()).isEqualTo("gemini-2.5-pro");
        assertThat(extracted.usage().inputTokens()).isEqualTo(14);
        assertThat(extracted.usage().outputTokens()).isEqualTo(8);
        assertThat(extracted.usage().cacheReadTokens()).isEqualTo(3);
    }

    @Test
    void geminiStreamReportsTheLastChunksTotals() {
        var extracted = extract(Provider.GEMINI,
                "/v1beta/models/gemini-2.5-flash:streamGenerateContent", "text/event-stream", """
                data: {"candidates":[{"content":{"parts":[{"text":"a"}]}}],"modelVersion":"gemini-2.5-flash"}

                data: {"candidates":[{"content":{"parts":[{"text":"b"}]},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":6,"totalTokenCount":10}}

                """).orElseThrow();

        assertThat(extracted.usage().totalTokens()).isEqualTo(10);
    }

    @Test
    void geminiNonSseStreamingIsAJsonArray() {
        var extracted = extract(Provider.GEMINI,
                "/v1beta/models/gemini-2.5-flash:streamGenerateContent", "application/json", """
                [{"candidates":[{"content":{"parts":[{"text":"a"}]}}]},
                 {"candidates":[{"content":{"parts":[{"text":"b"}]}}],
                  "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":6,"totalTokenCount":10}}]""")
                .orElseThrow();

        assertThat(extracted.usage().totalTokens()).isEqualTo(10);
        // The model came from the URL, since this shape does not repeat modelVersion.
        assertThat(extracted.model()).isEqualTo("gemini-2.5-flash");
    }

    // --- Bedrock -----------------------------------------------------------------------

    @Test
    void bedrockConverseTakesTheModelFromTheUrl() {
        var extracted = extract(Provider.BEDROCK,
                "/model/anthropic.claude-sonnet-4-5-20250929-v1:0/converse", "application/json", """
                {"output":{},"stopReason":"end_turn",
                 "usage":{"inputTokens":30,"outputTokens":12,"totalTokens":42,
                          "cacheReadInputTokens":5,"cacheWriteInputTokens":7}}""")
                .orElseThrow();

        assertThat(extracted.model()).isEqualTo("anthropic.claude-sonnet-4-5-20250929-v1:0");
        assertThat(extracted.usage().inputTokens()).isEqualTo(30);
        assertThat(extracted.usage().cacheReadTokens()).isEqualTo(5);
        assertThat(extracted.usage().cacheWriteTokens()).isEqualTo(7);
    }

    @Test
    void bedrockConverseStreamReportsFromItsMetadataEvent() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        EventStreamEncoder.writeEvent(out, "messageStart",
                "{\"role\":\"assistant\"}".getBytes(StandardCharsets.UTF_8));
        EventStreamEncoder.writeEvent(out, "metadata",
                "{\"usage\":{\"inputTokens\":8,\"outputTokens\":4,\"totalTokens\":12}}"
                        .getBytes(StandardCharsets.UTF_8));

        var extracted = extractor.extract(Provider.BEDROCK,
                "/model/amazon.nova-pro-v1:0/converse-stream",
                "application/vnd.amazon.eventstream", out.toByteArray()).orElseThrow();

        assertThat(extracted.model()).isEqualTo("amazon.nova-pro-v1:0");
        assertThat(extracted.usage().totalTokens()).isEqualTo(12);
    }

    @Test
    void bedrockInvokeWithResponseStreamUnwrapsTheNativeChunks() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        String nativeEvent = "{\"usage\":{\"input_tokens\":9,\"output_tokens\":5}}";
        String wrapper = "{\"bytes\":\"" + Base64.getEncoder()
                .encodeToString(nativeEvent.getBytes(StandardCharsets.UTF_8)) + "\"}";
        EventStreamEncoder.writeEvent(out, "chunk", wrapper.getBytes(StandardCharsets.UTF_8));

        var extracted = extractor.extract(Provider.BEDROCK,
                "/model/anthropic.claude-sonnet-4-5-20250929-v1:0/invoke-with-response-stream",
                "application/vnd.amazon.eventstream", out.toByteArray()).orElseThrow();

        assertThat(extracted.usage().inputTokens()).isEqualTo(9);
        assertThat(extracted.usage().outputTokens()).isEqualTo(5);
    }

    @Test
    void bedrockInvokeModelHandlesEachModelFamilysNativeShape() {
        var anthropic = extract(Provider.BEDROCK, "/model/anthropic.claude-x/invoke",
                "application/json", """
                {"type":"message","usage":{"input_tokens":4,"output_tokens":6}}""").orElseThrow();
        assertThat(anthropic.usage().totalTokens()).isEqualTo(10);

        var titan = extract(Provider.BEDROCK, "/model/amazon.titan-text-express-v1/invoke",
                "application/json", """
                {"inputTextTokenCount":7,"results":[{"tokenCount":3,"outputText":"hi"}]}""")
                .orElseThrow();
        assertThat(titan.usage().inputTokens()).isEqualTo(7);
        assertThat(titan.usage().outputTokens()).isEqualTo(3);

        var llama = extract(Provider.BEDROCK, "/model/meta.llama3-70b-instruct-v1:0/invoke",
                "application/json", """
                {"generation":"hi","prompt_token_count":11,"generation_token_count":2}""")
                .orElseThrow();
        assertThat(llama.usage().inputTokens()).isEqualTo(11);
        assertThat(llama.usage().outputTokens()).isEqualTo(2);

        var nova = extract(Provider.BEDROCK, "/model/amazon.nova-pro-v1:0/invoke",
                "application/json", """
                {"output":{},"stopReason":"end_turn",
                 "usage":{"inputTokens":13,"outputTokens":5,"totalTokens":18}}""").orElseThrow();
        assertThat(nova.usage().totalTokens()).isEqualTo(18);
    }

    // --- robustness --------------------------------------------------------------------

    @Test
    void responsesWithoutUsageAreSkippedRatherThanRecordedAsZero() {
        assertThat(extract(Provider.OPENAI, "/v1/models", "application/json", """
                {"object":"list","data":[]}""")).isEmpty();
        assertThat(extract(Provider.BEDROCK, "/model/m/converse", "application/json", """
                {"output":{}}""")).isEmpty();
    }

    @Test
    void malformedBodiesNeverBreakTheCallThatCarriedThem() {
        assertThat(extract(Provider.OPENAI, "/v1/chat/completions", "application/json",
                "not json at all")).isEmpty();
        assertThat(extractor.extract(Provider.BEDROCK, "/model/m/converse-stream",
                "application/vnd.amazon.eventstream", new byte[] {1, 2, 3})).isEmpty();
        assertThat(extractor.extract(Provider.OPENAI, "/v1/chat/completions", "application/json",
                new byte[0])).isEmpty();
    }
}
