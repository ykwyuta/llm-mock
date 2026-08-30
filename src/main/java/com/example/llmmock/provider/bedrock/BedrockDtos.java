package com.example.llmmock.provider.bedrock;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/** Wire types for the Bedrock Runtime Converse API. Bedrock JSON is camelCase throughout. */
public final class BedrockDtos {

    private BedrockDtos() {
    }

    // --- requests --------------------------------------------------------------------

    public record ConverseRequest(
            List<Message> messages,
            List<SystemContentBlock> system,
            InferenceConfiguration inferenceConfig,
            ToolConfiguration toolConfig,
            JsonNode guardrailConfig,
            JsonNode additionalModelRequestFields,
            List<String> additionalModelResponseFieldPaths,
            JsonNode requestMetadata,
            JsonNode performanceConfig) {
    }

    public record Message(String role, List<ContentBlock> content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlock(String text, ToolUseBlock toolUse, JsonNode toolResult,
                               JsonNode image, JsonNode document) {

        public static ContentBlock text(String text) {
            return new ContentBlock(text, null, null, null, null);
        }

        public static ContentBlock toolUse(ToolUseBlock toolUse) {
            return new ContentBlock(null, toolUse, null, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolUseBlock(String toolUseId, String name, JsonNode input) {
    }

    public record SystemContentBlock(String text, JsonNode guardContent) {
    }

    public record InferenceConfiguration(Integer maxTokens, Double temperature, Double topP,
                                         List<String> stopSequences) {
    }

    public record ToolConfiguration(List<Tool> tools, JsonNode toolChoice) {
    }

    public record Tool(ToolSpecification toolSpec) {
    }

    public record ToolSpecification(String name, String description, JsonNode inputSchema) {
    }

    // --- responses -------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConverseResponse(ConverseOutput output, String stopReason, TokenUsage usage,
                                   ConverseMetrics metrics,
                                   JsonNode additionalModelResponseFields) {
    }

    public record ConverseOutput(Message message) {
    }

    public record TokenUsage(int inputTokens, int outputTokens, int totalTokens) {
    }

    public record ConverseMetrics(long latencyMs) {
    }

    // --- streaming events ------------------------------------------------------------

    public record MessageStartEvent(String role) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlockStartEvent(int contentBlockIndex, ContentBlockStart start) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlockStart(ToolUseStart toolUse) {
    }

    public record ToolUseStart(String toolUseId, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlockDeltaEvent(int contentBlockIndex, ContentBlockDelta delta) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentBlockDelta(String text, ToolUseDelta toolUse) {
    }

    /** {@code input} arrives as a partial JSON string, matching the real service. */
    public record ToolUseDelta(String input) {
    }

    public record ContentBlockStopEvent(int contentBlockIndex) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageStopEvent(String stopReason, JsonNode additionalModelResponseFields) {
    }

    public record MetadataEvent(TokenUsage usage, ConverseMetrics metrics) {
    }

    // --- errors ----------------------------------------------------------------------

    public record ErrorBody(String message) {
    }
}
