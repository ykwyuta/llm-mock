package io.github.ykwyuta.llmmock.provider.gemini;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.ykwyuta.llmmock.config.LlmMockProperties;
import io.github.ykwyuta.llmmock.config.ProviderApi;
import io.github.ykwyuta.llmmock.core.ChatRole;
import io.github.ykwyuta.llmmock.core.EmbeddingGenerator;
import io.github.ykwyuta.llmmock.core.FinishReason;
import io.github.ykwyuta.llmmock.core.Ids;
import io.github.ykwyuta.llmmock.core.MockApiException;
import io.github.ykwyuta.llmmock.core.MockCompletion;
import io.github.ykwyuta.llmmock.core.MockEngine;
import io.github.ykwyuta.llmmock.core.MockOverrides;
import io.github.ykwyuta.llmmock.core.MockRequest;
import io.github.ykwyuta.llmmock.core.Provider;
import io.github.ykwyuta.llmmock.core.TextChunker;
import io.github.ykwyuta.llmmock.core.TokenCounter;
import io.github.ykwyuta.llmmock.core.ToolCall;
import io.github.ykwyuta.llmmock.core.ToolSpec;
import io.github.ykwyuta.llmmock.provider.common.AuthGuard;
import io.github.ykwyuta.llmmock.provider.common.SseWriter;
import io.github.ykwyuta.llmmock.provider.common.StreamResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini-compatible surface. Point a Google GenAI SDK at
 * {@code http://localhost:8080/gemini} and its {@code /v1beta/models/...} paths line up.
 *
 * <p>Gemini encodes the operation as a {@code :method} suffix on the last path segment,
 * so one mapping per operation captures the model id from the same segment.
 */
@ProviderApi(Provider.GEMINI)
@RequestMapping("/v1beta")
public class GeminiController {

    private static final List<GeminiDtos.SafetyRating> SAFE_RATINGS = List.of(
            new GeminiDtos.SafetyRating("HARM_CATEGORY_HARASSMENT", "NEGLIGIBLE"),
            new GeminiDtos.SafetyRating("HARM_CATEGORY_HATE_SPEECH", "NEGLIGIBLE"),
            new GeminiDtos.SafetyRating("HARM_CATEGORY_SEXUALLY_EXPLICIT", "NEGLIGIBLE"),
            new GeminiDtos.SafetyRating("HARM_CATEGORY_DANGEROUS_CONTENT", "NEGLIGIBLE"));

    private final MockEngine engine;
    private final ObjectMapper mapper;
    private final AuthGuard authGuard;
    private final LlmMockProperties properties;
    private final TokenCounter tokenCounter;
    private final EmbeddingGenerator embeddings;

    public GeminiController(MockEngine engine, ObjectMapper mapper, AuthGuard authGuard,
                            LlmMockProperties properties, TokenCounter tokenCounter,
                            EmbeddingGenerator embeddings) {
        this.engine = engine;
        this.mapper = mapper;
        this.authGuard = authGuard;
        this.properties = properties;
        this.tokenCounter = tokenCounter;
        this.embeddings = embeddings;
    }

    // --- generateContent -------------------------------------------------------------

    @PostMapping("/models/{model}:generateContent")
    public GeminiDtos.GenerateContentResponse generateContent(
            @PathVariable String model,
            @RequestBody GeminiDtos.GenerateContentRequest body,
            HttpServletRequest http) {
        authGuard.check(Provider.GEMINI, http);
        MockRequest request = toMockRequest(model, body, "generateContent", false);
        return toResponse(request, engine.complete(request, MockOverrides.from(http)));
    }

    /**
     * Streaming variant. With {@code ?alt=sse} the response is Server-Sent Events; without
     * it Gemini returns one JSON array of the same objects, and both are supported here.
     */
    @PostMapping("/models/{model}:streamGenerateContent")
    public ResponseEntity<Void> streamGenerateContent(
            @PathVariable String model,
            @RequestBody GeminiDtos.GenerateContentRequest body,
            @RequestParam(name = "alt", required = false) String alt,
            HttpServletRequest http, HttpServletResponse response) throws IOException {
        authGuard.check(Provider.GEMINI, http);
        MockRequest request = toMockRequest(model, body, "streamGenerateContent", true);
        MockCompletion completion = engine.complete(request, MockOverrides.from(http));

        List<GeminiDtos.GenerateContentResponse> events = streamEvents(request, completion);
        boolean sse = "sse".equalsIgnoreCase(alt);
        long delay = properties.getStream().getDelayMs();

        if (sse) {
            OutputStream out = StreamResponse.begin(response, MediaType.TEXT_EVENT_STREAM_VALUE);
            SseWriter writer = new SseWriter(out, mapper);
            for (GeminiDtos.GenerateContentResponse event : events) {
                StreamResponse.pause(delay);
                writer.data(event);
            }
        } else {
            OutputStream out = StreamResponse.begin(response, MediaType.APPLICATION_JSON_VALUE);
            out.write('[');
            for (int i = 0; i < events.size(); i++) {
                StreamResponse.pause(delay);
                if (i > 0) {
                    out.write(',');
                }
                out.write(mapper.writeValueAsString(events.get(i)).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write(']');
            out.flush();
        }
        return null;
    }

    private List<GeminiDtos.GenerateContentResponse> streamEvents(MockRequest request,
                                                                  MockCompletion completion) {
        String responseId = Ids.hex(11);
        List<GeminiDtos.GenerateContentResponse> events = new ArrayList<>();
        List<String> pieces = TextChunker.chunk(completion.text(),
                properties.getStream().getWordsPerChunk());

        for (int i = 0; i < pieces.size(); i++) {
            boolean last = i == pieces.size() - 1 && completion.toolCalls().isEmpty();
            events.add(chunkResponse(request, responseId, List.of(GeminiDtos.Part.text(pieces.get(i))),
                    last ? finishReason(completion.finishReason()) : null,
                    last ? completion : null));
        }
        for (int i = 0; i < completion.toolCalls().size(); i++) {
            ToolCall call = completion.toolCalls().get(i);
            boolean last = i == completion.toolCalls().size() - 1;
            events.add(chunkResponse(request, responseId,
                    List.of(GeminiDtos.Part.functionCall(call.name(), parseArguments(call.arguments()))),
                    last ? finishReason(completion.finishReason()) : null,
                    last ? completion : null));
        }
        if (events.isEmpty()) {
            events.add(chunkResponse(request, responseId, List.of(GeminiDtos.Part.text("")),
                    finishReason(completion.finishReason()), completion));
        }
        return events;
    }

    private GeminiDtos.GenerateContentResponse chunkResponse(MockRequest request, String responseId,
                                                             List<GeminiDtos.Part> parts,
                                                             String finishReason,
                                                             MockCompletion finalCompletion) {
        GeminiDtos.Candidate candidate = new GeminiDtos.Candidate(
                new GeminiDtos.Content("model", parts), finishReason, 0,
                finishReason == null ? null : SAFE_RATINGS);
        return new GeminiDtos.GenerateContentResponse(List.of(candidate),
                finalCompletion == null ? null : usage(finalCompletion),
                request.model(), responseId);
    }

    // --- countTokens -----------------------------------------------------------------

    @PostMapping("/models/{model}:countTokens")
    public GeminiDtos.CountTokensResponse countTokens(@PathVariable String model,
                                                      @RequestBody GeminiDtos.CountTokensRequest body,
                                                      HttpServletRequest http) {
        authGuard.check(Provider.GEMINI, http);
        List<GeminiDtos.Content> contents = body.contents();
        if (contents == null && body.generateContentRequest() != null) {
            // The SDKs may wrap the payload; unwrap it so both shapes count the same.
            JsonNode nested = body.generateContentRequest().get("contents");
            if (nested != null) {
                contents = mapper.convertValue(nested,
                        mapper.getTypeFactory().constructCollectionType(List.class, GeminiDtos.Content.class));
            }
        }
        GeminiDtos.GenerateContentRequest asGenerate = new GeminiDtos.GenerateContentRequest(
                contents, null, null, null, null, null, null);
        MockRequest request = toMockRequest(model, asGenerate, "countTokens", false);
        int tokens = tokenCounter.countRequest(request);
        engine.recordSimple(Provider.GEMINI, "countTokens", normaliseModel(model), 200,
                tokens + " total tokens");
        return new GeminiDtos.CountTokensResponse(tokens);
    }

    // --- embedContent ----------------------------------------------------------------

    @PostMapping("/models/{model}:embedContent")
    public GeminiDtos.EmbedContentResponse embedContent(@PathVariable String model,
                                                        @RequestBody GeminiDtos.EmbedContentRequest body,
                                                        HttpServletRequest http) {
        authGuard.check(Provider.GEMINI, http);
        if (body == null || body.content() == null) {
            throw MockApiException.invalidRequest("content is required");
        }
        int dimensions = body.outputDimensionality() != null ? body.outputDimensionality()
                : properties.getEmbedding().getGeminiDimensions();
        String text = flatten(body.content());
        engine.recordSimple(Provider.GEMINI, "embedContent", normaliseModel(model), 200,
                "embedding of dimension " + dimensions);
        return new GeminiDtos.EmbedContentResponse(
                new GeminiDtos.ContentEmbedding(embeddings.embed(text, dimensions)));
    }

    @PostMapping("/models/{model}:batchEmbedContents")
    public GeminiDtos.BatchEmbedContentsResponse batchEmbedContents(
            @PathVariable String model,
            @RequestBody GeminiDtos.BatchEmbedContentsRequest body,
            HttpServletRequest http) {
        authGuard.check(Provider.GEMINI, http);
        if (body == null || body.requests() == null || body.requests().isEmpty()) {
            throw MockApiException.invalidRequest("requests is required and must not be empty");
        }
        List<GeminiDtos.ContentEmbedding> results = new ArrayList<>();
        for (GeminiDtos.EmbedContentRequest request : body.requests()) {
            int dimensions = request.outputDimensionality() != null ? request.outputDimensionality()
                    : properties.getEmbedding().getGeminiDimensions();
            results.add(new GeminiDtos.ContentEmbedding(
                    embeddings.embed(flatten(request.content()), dimensions)));
        }
        engine.recordSimple(Provider.GEMINI, "batchEmbedContents", normaliseModel(model), 200,
                results.size() + " embedding(s)");
        return new GeminiDtos.BatchEmbedContentsResponse(results);
    }

    // --- models ----------------------------------------------------------------------

    @GetMapping("/models")
    public GeminiDtos.ModelList models() {
        return new GeminiDtos.ModelList(properties.getModels().getGemini().stream()
                .map(id -> new GeminiDtos.Model("models/" + id, "001", id,
                        "llm-mock stand-in for " + id, 1_048_576, 8_192,
                        List.of("generateContent", "streamGenerateContent", "countTokens")))
                .toList());
    }

    @GetMapping("/models/{model}")
    public GeminiDtos.Model model(@PathVariable String model) {
        String id = normaliseModel(model);
        if (!properties.getModels().getGemini().contains(id)) {
            throw new MockApiException(404, "not_found",
                    "models/" + id + " is not found for API version v1beta");
        }
        return new GeminiDtos.Model("models/" + id, "001", id, "llm-mock stand-in for " + id,
                1_048_576, 8_192, List.of("generateContent", "streamGenerateContent", "countTokens"));
    }

    // --- mapping ---------------------------------------------------------------------

    private MockRequest toMockRequest(String model, GeminiDtos.GenerateContentRequest body,
                                      String endpoint, boolean stream) {
        if (body == null || body.contents() == null || body.contents().isEmpty()) {
            throw MockApiException.invalidRequest("contents is required and must not be empty");
        }
        GeminiDtos.GenerationConfig config = body.generationConfig();
        MockRequest.Builder builder = MockRequest.builder(Provider.GEMINI, endpoint)
                .model(normaliseModel(model))
                .stream(stream)
                .message(ChatRole.SYSTEM, flatten(body.systemInstruction()));
        if (config != null) {
            builder.maxTokens(config.maxOutputTokens())
                    .temperature(config.temperature())
                    .topP(config.topP())
                    .stopSequences(config.stopSequences());
        }
        for (GeminiDtos.Content content : body.contents()) {
            builder.message(ChatRole.from(content.role()), flatten(content));
        }
        if (body.tools() != null) {
            for (GeminiDtos.Tool tool : body.tools()) {
                if (tool.functionDeclarations() != null) {
                    for (GeminiDtos.FunctionDeclaration declaration : tool.functionDeclarations()) {
                        builder.tool(new ToolSpec(declaration.name(), declaration.description(), null));
                    }
                }
            }
        }
        return builder.build();
    }

    private GeminiDtos.GenerateContentResponse toResponse(MockRequest request, MockCompletion completion) {
        List<GeminiDtos.Part> parts = new ArrayList<>();
        if (completion.text() != null && !completion.text().isEmpty()) {
            parts.add(GeminiDtos.Part.text(completion.text()));
        }
        for (ToolCall call : completion.toolCalls()) {
            parts.add(GeminiDtos.Part.functionCall(call.name(), parseArguments(call.arguments())));
        }
        GeminiDtos.Candidate candidate = new GeminiDtos.Candidate(
                new GeminiDtos.Content("model", parts), finishReason(completion.finishReason()), 0,
                SAFE_RATINGS);
        return new GeminiDtos.GenerateContentResponse(List.of(candidate), usage(completion),
                request.model(), Ids.hex(11));
    }

    // --- helpers ---------------------------------------------------------------------

    /** Accepts both {@code gemini-2.5-pro} and the fully qualified {@code models/gemini-2.5-pro}. */
    private String normaliseModel(String model) {
        if (model == null) {
            throw MockApiException.invalidRequest("model is required");
        }
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }

    private String flatten(GeminiDtos.Content content) {
        if (content == null || content.parts() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (GeminiDtos.Part part : content.parts()) {
            if (part != null && part.text() != null && !part.text().isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(part.text());
            }
        }
        return sb.toString();
    }

    private GeminiDtos.UsageMetadata usage(MockCompletion completion) {
        return new GeminiDtos.UsageMetadata(completion.usage().inputTokens(),
                completion.usage().outputTokens(), completion.usage().totalTokens());
    }

    private JsonNode parseArguments(String arguments) {
        try {
            JsonNode node = mapper.readTree(arguments == null ? "{}" : arguments);
            return node.isObject() ? node : mapper.createObjectNode().set("value", node);
        } catch (RuntimeException ex) {
            return mapper.createObjectNode().put("raw", arguments);
        }
    }

    static String finishReason(FinishReason reason) {
        return switch (reason) {
            case LENGTH -> "MAX_TOKENS";
            case CONTENT_FILTER -> "SAFETY";
            // Gemini has no distinct tool-call stop reason: a functionCall part ends with STOP.
            case STOP, STOP_SEQUENCE, TOOL_USE -> "STOP";
        };
    }
}
