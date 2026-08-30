package io.github.ykwyuta.llmmock.provider.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * Wire types for the OpenAI REST API. Field names follow the published spec exactly,
 * including the snake_case that the official SDKs expect.
 */
public final class OpenAiDtos {

    private OpenAiDtos() {
    }

    // --- requests --------------------------------------------------------------------

    public record ChatCompletionRequest(
            String model,
            List<Message> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            Integer n,
            Boolean stream,
            @JsonProperty("stream_options") StreamOptions streamOptions,
            JsonNode stop,
            List<Tool> tools,
            @JsonProperty("tool_choice") JsonNode toolChoice) {
    }

    public record Message(String role, JsonNode content, String name,
                          @JsonProperty("tool_call_id") String toolCallId) {
    }

    public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
    }

    public record Tool(String type, FunctionDef function) {
    }

    public record FunctionDef(String name, String description, JsonNode parameters) {
    }

    public record CompletionRequest(
            String model,
            JsonNode prompt,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature,
            Boolean stream) {
    }

    public record EmbeddingRequest(
            String model,
            JsonNode input,
            Integer dimensions,
            @JsonProperty("encoding_format") String encodingFormat) {
    }

    // --- responses -------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage,
            @JsonProperty("system_fingerprint") String systemFingerprint) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(int index, ResponseMessage message,
                         @JsonProperty("finish_reason") String finishReason,
                         @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode logprobs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResponseMessage(String role, String content,
                                  @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                                  String refusal) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCall(String id, String type, FunctionCall function, Integer index) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionCall(String name, String arguments) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                        @JsonProperty("completion_tokens") int completionTokens,
                        @JsonProperty("total_tokens") int totalTokens) {
    }

    // --- streaming -------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionChunk(
            String id,
            String object,
            long created,
            String model,
            List<ChunkChoice> choices,
            Usage usage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkChoice(int index, Delta delta,
                              @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content,
                        @JsonProperty("tool_calls") List<ToolCall> toolCalls) {
    }

    // --- legacy completions ----------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompletionResponse(String id, String object, long created, String model,
                                     List<TextChoice> choices, Usage usage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextChoice(String text, int index, JsonNode logprobs,
                             @JsonProperty("finish_reason") String finishReason) {
    }

    // --- embeddings ------------------------------------------------------------------

    public record EmbeddingResponse(String object, List<EmbeddingData> data, String model,
                                    Usage usage) {
    }

    public record EmbeddingData(String object, int index, double[] embedding) {
    }

    // --- models ----------------------------------------------------------------------

    public record ModelList(String object, List<Model> data) {
    }

    public record Model(String id, String object, long created,
                        @JsonProperty("owned_by") String ownedBy) {
    }

    // --- errors ----------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErrorEnvelope(ErrorBody error) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErrorBody(String message, String type, String param, String code) {
    }
}
