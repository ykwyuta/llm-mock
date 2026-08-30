package com.example.llmmock.provider.openai;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import com.example.llmmock.config.LlmMockProperties;
import com.example.llmmock.config.ProviderApi;
import com.example.llmmock.core.ChatRole;
import com.example.llmmock.core.EmbeddingGenerator;
import com.example.llmmock.core.FinishReason;
import com.example.llmmock.core.Ids;
import com.example.llmmock.core.MockApiException;
import com.example.llmmock.core.MockCompletion;
import com.example.llmmock.core.MockEngine;
import com.example.llmmock.core.MockOverrides;
import com.example.llmmock.core.MockRequest;
import com.example.llmmock.core.Provider;
import com.example.llmmock.core.TextChunker;
import com.example.llmmock.core.TokenCounter;
import com.example.llmmock.core.ToolSpec;
import com.example.llmmock.provider.common.AuthGuard;
import com.example.llmmock.provider.common.JsonText;
import com.example.llmmock.provider.common.SseWriter;
import com.example.llmmock.provider.common.StreamResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OpenAI-compatible surface. Point an OpenAI SDK at
 * {@code http://localhost:8080/openai/v1} and its default paths line up.
 */
@ProviderApi(Provider.OPENAI)
@RequestMapping("/v1")
public class OpenAiController {

    private static final String SYSTEM_FINGERPRINT = "fp_llmmock";

    private final MockEngine engine;
    private final ObjectMapper mapper;
    private final AuthGuard authGuard;
    private final LlmMockProperties properties;
    private final EmbeddingGenerator embeddings;
    private final TokenCounter tokenCounter;

    public OpenAiController(MockEngine engine, ObjectMapper mapper, AuthGuard authGuard,
                            LlmMockProperties properties, EmbeddingGenerator embeddings,
                            TokenCounter tokenCounter) {
        this.engine = engine;
        this.mapper = mapper;
        this.authGuard = authGuard;
        this.properties = properties;
        this.embeddings = embeddings;
        this.tokenCounter = tokenCounter;
    }

    // --- chat completions ------------------------------------------------------------

    @PostMapping("/chat/completions")
    public ResponseEntity<OpenAiDtos.ChatCompletionResponse> chatCompletions(
            @RequestBody OpenAiDtos.ChatCompletionRequest body,
            HttpServletRequest http, HttpServletResponse response) throws IOException {
        authGuard.check(Provider.OPENAI, http);
        MockRequest request = toMockRequest(body);
        // Resolve up front so a simulated failure becomes a real HTTP error status rather
        // than an exception thrown halfway through an already-committed stream.
        MockCompletion completion = engine.complete(request, MockOverrides.from(http));

        if (!request.stream()) {
            return ResponseEntity.ok(toResponse(request, completion));
        }
        boolean includeUsage = body.streamOptions() != null
                && Boolean.TRUE.equals(body.streamOptions().includeUsage());
        writeStream(request, completion, includeUsage,
                StreamResponse.begin(response, MediaType.TEXT_EVENT_STREAM_VALUE));
        return null;
    }

    private MockRequest toMockRequest(OpenAiDtos.ChatCompletionRequest body) {
        if (body == null || body.model() == null || body.model().isBlank()) {
            throw MockApiException.invalidRequest("'model' is a required property");
        }
        if (body.messages() == null || body.messages().isEmpty()) {
            throw MockApiException.invalidRequest("'messages' must contain at least one message");
        }
        MockRequest.Builder builder = MockRequest.builder(Provider.OPENAI, "chat.completions")
                .model(body.model())
                .maxTokens(body.maxCompletionTokens() != null ? body.maxCompletionTokens() : body.maxTokens())
                .temperature(body.temperature())
                .topP(body.topP())
                .stopSequences(toStringList(body.stop()))
                .stream(Boolean.TRUE.equals(body.stream()));
        for (OpenAiDtos.Message message : body.messages()) {
            builder.message(ChatRole.from(message.role()), JsonText.flatten(message.content()));
        }
        if (body.tools() != null) {
            for (OpenAiDtos.Tool tool : body.tools()) {
                if (tool.function() != null) {
                    builder.tool(new ToolSpec(tool.function().name(), tool.function().description(), null));
                }
            }
        }
        return builder.build();
    }

    private OpenAiDtos.ChatCompletionResponse toResponse(MockRequest request, MockCompletion completion) {
        List<OpenAiDtos.ToolCall> toolCalls = completion.toolCalls().isEmpty() ? null
                : completion.toolCalls().stream()
                        .map(call -> new OpenAiDtos.ToolCall(Ids.openAiToolCall(), "function",
                                new OpenAiDtos.FunctionCall(call.name(), call.arguments()), null))
                        .toList();
        OpenAiDtos.ResponseMessage message = new OpenAiDtos.ResponseMessage("assistant",
                completion.text(), toolCalls, null);
        OpenAiDtos.Choice choice = new OpenAiDtos.Choice(0, message,
                finishReason(completion.finishReason()), null);
        return new OpenAiDtos.ChatCompletionResponse("chatcmpl-" + completion.id(), "chat.completion",
                Instant.now().getEpochSecond(), request.model(), List.of(choice),
                usage(completion), SYSTEM_FINGERPRINT);
    }

    private void writeStream(MockRequest request, MockCompletion completion, boolean includeUsage,
                             OutputStream out) throws IOException {
        String id = "chatcmpl-" + completion.id();
        long created = Instant.now().getEpochSecond();
        String model = request.model();
        long delay = properties.getStream().getDelayMs();
        SseWriter sse = new SseWriter(out, mapper);

        sse.data(chunk(id, created, model, new OpenAiDtos.Delta("assistant", "", null), null));

        for (String piece : TextChunker.chunk(completion.text(),
                properties.getStream().getWordsPerChunk())) {
            StreamResponse.pause(delay);
            sse.data(chunk(id, created, model, new OpenAiDtos.Delta(null, piece, null), null));
        }
        for (com.example.llmmock.core.ToolCall call : completion.toolCalls()) {
            StreamResponse.pause(delay);
            // The name arrives first with an empty argument string and the arguments follow
            // in their own chunk - the shape SDK accumulators are written against.
            OpenAiDtos.ToolCall head = new OpenAiDtos.ToolCall(Ids.openAiToolCall(), "function",
                    new OpenAiDtos.FunctionCall(call.name(), ""), 0);
            sse.data(chunk(id, created, model, new OpenAiDtos.Delta(null, null, List.of(head)), null));
            OpenAiDtos.ToolCall args = new OpenAiDtos.ToolCall(null, null,
                    new OpenAiDtos.FunctionCall(null, call.arguments()), 0);
            sse.data(chunk(id, created, model, new OpenAiDtos.Delta(null, null, List.of(args)), null));
        }

        sse.data(chunk(id, created, model, new OpenAiDtos.Delta(null, null, null),
                finishReason(completion.finishReason())));
        if (includeUsage) {
            sse.data(new OpenAiDtos.ChatCompletionChunk(id, "chat.completion.chunk", created, model,
                    List.of(), usage(completion)));
        }
        sse.raw("[DONE]");
    }

    private OpenAiDtos.ChatCompletionChunk chunk(String id, long created, String model,
                                                 OpenAiDtos.Delta delta, String finishReason) {
        return new OpenAiDtos.ChatCompletionChunk(id, "chat.completion.chunk", created, model,
                List.of(new OpenAiDtos.ChunkChoice(0, delta, finishReason)), null);
    }

    // --- legacy completions ----------------------------------------------------------

    @PostMapping("/completions")
    public OpenAiDtos.CompletionResponse completions(@RequestBody OpenAiDtos.CompletionRequest body,
                                                     HttpServletRequest http) {
        authGuard.check(Provider.OPENAI, http);
        if (body == null || body.model() == null || body.model().isBlank()) {
            throw MockApiException.invalidRequest("'model' is a required property");
        }
        MockRequest request = MockRequest.builder(Provider.OPENAI, "completions")
                .model(body.model())
                .message(ChatRole.USER, JsonText.flatten(body.prompt()))
                .maxTokens(body.maxTokens())
                .temperature(body.temperature())
                .build();
        MockCompletion completion = engine.complete(request, MockOverrides.from(http));
        OpenAiDtos.TextChoice choice = new OpenAiDtos.TextChoice(completion.text(), 0, null,
                finishReason(completion.finishReason()));
        return new OpenAiDtos.CompletionResponse("cmpl-" + completion.id(), "text_completion",
                Instant.now().getEpochSecond(), body.model(), List.of(choice), usage(completion));
    }

    // --- embeddings ------------------------------------------------------------------

    @PostMapping("/embeddings")
    public OpenAiDtos.EmbeddingResponse embeddings(@RequestBody OpenAiDtos.EmbeddingRequest body,
                                                   HttpServletRequest http) {
        authGuard.check(Provider.OPENAI, http);
        if (body == null || body.model() == null || body.model().isBlank()) {
            throw MockApiException.invalidRequest("'model' is a required property");
        }
        List<String> inputs = toStringList(body.input());
        if (inputs.isEmpty()) {
            throw MockApiException.invalidRequest("'input' must not be empty");
        }
        int dimensions = body.dimensions() != null ? body.dimensions()
                : properties.getEmbedding().getOpenaiDimensions();
        List<OpenAiDtos.EmbeddingData> data = new ArrayList<>();
        int promptTokens = 0;
        for (int i = 0; i < inputs.size(); i++) {
            data.add(new OpenAiDtos.EmbeddingData("embedding", i,
                    embeddings.embed(inputs.get(i), dimensions)));
            promptTokens += tokenCounter.countText(inputs.get(i));
        }
        engine.recordSimple(Provider.OPENAI, "embeddings", body.model(), 200,
                inputs.size() + " embedding(s) of dimension " + dimensions);
        return new OpenAiDtos.EmbeddingResponse("list", data, body.model(),
                new OpenAiDtos.Usage(promptTokens, 0, promptTokens));
    }

    // --- models ----------------------------------------------------------------------

    @GetMapping("/models")
    public OpenAiDtos.ModelList models() {
        return new OpenAiDtos.ModelList("list", properties.getModels().getOpenai().stream()
                .map(this::modelEntry)
                .toList());
    }

    @GetMapping("/models/{model}")
    public OpenAiDtos.Model model(@PathVariable("model") String modelId) {
        if (!properties.getModels().getOpenai().contains(modelId)) {
            throw new MockApiException(404, "not_found",
                    "The model '" + modelId + "' does not exist");
        }
        return modelEntry(modelId);
    }

    private OpenAiDtos.Model modelEntry(String id) {
        return new OpenAiDtos.Model(id, "model", 1700000000L, "llm-mock");
    }

    // --- helpers ---------------------------------------------------------------------

    private OpenAiDtos.Usage usage(MockCompletion completion) {
        return new OpenAiDtos.Usage(completion.usage().inputTokens(),
                completion.usage().outputTokens(), completion.usage().totalTokens());
    }

    static String finishReason(FinishReason reason) {
        return switch (reason) {
            case LENGTH -> "length";
            case TOOL_USE -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            case STOP, STOP_SEQUENCE -> "stop";
        };
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(element -> values.add(element.isString() ? element.asString() : element.toString()));
        } else {
            values.add(node.isString() ? node.asString() : node.toString());
        }
        return values;
    }

}
