package com.example.llmmock.provider.anthropic;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/** Wire types for the Anthropic Messages API ({@code anthropic-version: 2023-06-01}). */
public final class AnthropicDtos {

    private AnthropicDtos() {
    }

    // --- requests --------------------------------------------------------------------

    public record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") Integer maxTokens,
            List<Message> messages,
            JsonNode system,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            @JsonProperty("top_k") Integer topK,
            @JsonProperty("stop_sequences") List<String> stopSequences,
            Boolean stream,
            List<Tool> tools,
            @JsonProperty("tool_choice") JsonNode toolChoice,
            JsonNode metadata) {
    }

    public record Message(String role, JsonNode content) {
    }

    public record Tool(String name, String description,
                       @JsonProperty("input_schema") JsonNode inputSchema) {
    }

    public record CountTokensRequest(String model, List<Message> messages, JsonNode system,
                                     List<Tool> tools) {
    }

    // --- responses -------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageResponse(
            String id,
            String type,
            String role,
            String model,
            List<ContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @JsonProperty("stop_sequence") String stopSequence,
            Usage usage) {
    }

    /** Serves as both a text block and a {@code tool_use} block; unused fields drop out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlock(String type, String text, String id, String name, JsonNode input) {

        public static ContentBlock text(String text) {
            return new ContentBlock("text", text, null, null, null);
        }

        public static ContentBlock toolUse(String id, String name, JsonNode input) {
            return new ContentBlock("tool_use", null, id, name, input);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(@JsonProperty("input_tokens") Integer inputTokens,
                        @JsonProperty("output_tokens") Integer outputTokens,
                        @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
                        @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens) {
    }

    public record CountTokensResponse(@JsonProperty("input_tokens") int inputTokens) {
    }

    // --- streaming events ------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageStartEvent(String type, MessageResponse message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlockStartEvent(String type, int index,
                                         @JsonProperty("content_block") ContentBlock contentBlock) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlockDeltaEvent(String type, int index, Delta delta) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String type, String text,
                        @JsonProperty("partial_json") String partialJson) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlockStopEvent(String type, int index) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageDeltaEvent(String type, MessageDelta delta, Usage usage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageDelta(@JsonProperty("stop_reason") String stopReason,
                               @JsonProperty("stop_sequence") String stopSequence) {
    }

    public record SimpleEvent(String type) {
    }

    // --- models ----------------------------------------------------------------------

    public record ModelList(List<Model> data,
                            @JsonProperty("has_more") boolean hasMore,
                            @JsonProperty("first_id") String firstId,
                            @JsonProperty("last_id") String lastId) {
    }

    public record Model(String type, String id,
                        @JsonProperty("display_name") String displayName,
                        @JsonProperty("created_at") String createdAt) {
    }

    // --- errors ----------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorEnvelope(String type, ErrorBody error,
                                @JsonProperty("request_id") String requestId) {
    }

    public record ErrorBody(String type, String message) {
    }
}
