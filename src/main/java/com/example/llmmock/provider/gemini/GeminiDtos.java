package com.example.llmmock.provider.gemini;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * Wire types for the Gemini generative language API.
 *
 * <p>The REST surface emits camelCase but also accepts the proto snake_case spellings, so
 * inbound fields carry a {@link JsonAlias} for the snake_case form.
 */
public final class GeminiDtos {

    private GeminiDtos() {
    }

    // --- requests --------------------------------------------------------------------

    public record GenerateContentRequest(
            List<Content> contents,
            @JsonAlias("system_instruction") Content systemInstruction,
            @JsonAlias("generation_config") GenerationConfig generationConfig,
            List<Tool> tools,
            @JsonAlias("tool_config") JsonNode toolConfig,
            @JsonAlias("safety_settings") List<JsonNode> safetySettings,
            @JsonAlias("cached_content") String cachedContent) {
    }

    public record Content(String role, List<Part> parts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(String text,
                       @JsonAlias("inline_data") JsonNode inlineData,
                       @JsonAlias("file_data") JsonNode fileData,
                       @JsonAlias("function_call") FunctionCall functionCall,
                       @JsonAlias("function_response") JsonNode functionResponse) {

        public static Part text(String text) {
            return new Part(text, null, null, null, null);
        }

        public static Part functionCall(String name, JsonNode args) {
            return new Part(null, null, null, new FunctionCall(name, args), null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionCall(String name, JsonNode args) {
    }

    public record GenerationConfig(
            Double temperature,
            @JsonAlias("top_p") Double topP,
            @JsonAlias("top_k") Integer topK,
            @JsonAlias("max_output_tokens") Integer maxOutputTokens,
            @JsonAlias("candidate_count") Integer candidateCount,
            @JsonAlias("stop_sequences") List<String> stopSequences,
            @JsonAlias("response_mime_type") String responseMimeType,
            @JsonAlias("response_schema") JsonNode responseSchema) {
    }

    public record Tool(@JsonAlias("function_declarations") List<FunctionDeclaration> functionDeclarations) {
    }

    public record FunctionDeclaration(String name, String description, JsonNode parameters) {
    }

    public record CountTokensRequest(List<Content> contents,
                                     @JsonAlias("generate_content_request") JsonNode generateContentRequest) {
    }

    public record EmbedContentRequest(String model, Content content,
                                      @JsonAlias("task_type") String taskType,
                                      @JsonAlias("output_dimensionality") Integer outputDimensionality) {
    }

    /**
     * Batch form of the above. The official SDKs route even a single-input
     * {@code embedContent} call through {@code :batchEmbedContents}, so this is the
     * endpoint that actually has to work for them.
     */
    public record BatchEmbedContentsRequest(List<EmbedContentRequest> requests) {
    }

    // --- responses -------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerateContentResponse(
            List<Candidate> candidates,
            UsageMetadata usageMetadata,
            String modelVersion,
            String responseId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Candidate(Content content, String finishReason, int index,
                            List<SafetyRating> safetyRatings) {
    }

    public record SafetyRating(String category, String probability) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageMetadata(Integer promptTokenCount, Integer candidatesTokenCount,
                                Integer totalTokenCount) {
    }

    public record CountTokensResponse(int totalTokens) {
    }

    public record EmbedContentResponse(ContentEmbedding embedding) {
    }

    public record BatchEmbedContentsResponse(List<ContentEmbedding> embeddings) {
    }

    public record ContentEmbedding(double[] values) {
    }

    // --- models ----------------------------------------------------------------------

    public record ModelList(List<Model> models) {
    }

    public record Model(String name, String version, String displayName, String description,
                        int inputTokenLimit, int outputTokenLimit,
                        List<String> supportedGenerationMethods) {
    }

    // --- errors ----------------------------------------------------------------------

    public record ErrorEnvelope(ErrorBody error) {
    }

    public record ErrorBody(int code, String message, String status, List<Map<String, Object>> details) {
    }
}
