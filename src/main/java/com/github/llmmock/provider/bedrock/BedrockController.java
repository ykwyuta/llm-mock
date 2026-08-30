package com.github.llmmock.provider.bedrock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.github.llmmock.config.LlmMockProperties;
import com.github.llmmock.config.ProviderApi;
import com.github.llmmock.core.ChatRole;
import com.github.llmmock.core.FinishReason;
import com.github.llmmock.core.Ids;
import com.github.llmmock.core.MockApiException;
import com.github.llmmock.core.MockCompletion;
import com.github.llmmock.core.MockEngine;
import com.github.llmmock.core.MockOverrides;
import com.github.llmmock.core.MockRequest;
import com.github.llmmock.core.Provider;
import com.github.llmmock.core.TextChunker;
import com.github.llmmock.core.ToolCall;
import com.github.llmmock.core.ToolSpec;
import com.github.llmmock.provider.anthropic.AnthropicWire;
import com.github.llmmock.provider.common.AuthGuard;
import com.github.llmmock.provider.common.StreamResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Amazon Bedrock Runtime surface. Point an AWS SDK's Bedrock Runtime endpoint override at
 * {@code http://localhost:8080/bedrock}.
 *
 * <p>SigV4 signatures are accepted but never verified - the mock only optionally checks
 * that an {@code Authorization} header is present.
 */
@ProviderApi(Provider.BEDROCK)
public class BedrockController {

    private static final String EVENT_STREAM_CONTENT_TYPE = "application/vnd.amazon.eventstream";

    private final MockEngine engine;
    private final ObjectMapper mapper;
    private final AuthGuard authGuard;
    private final LlmMockProperties properties;

    public BedrockController(MockEngine engine, ObjectMapper mapper, AuthGuard authGuard,
                             LlmMockProperties properties) {
        this.engine = engine;
        this.mapper = mapper;
        this.authGuard = authGuard;
        this.properties = properties;
    }

    // --- Converse --------------------------------------------------------------------

    @PostMapping("/model/{modelId}/converse")
    public BedrockDtos.ConverseResponse converse(@PathVariable String modelId,
                                                 @RequestBody BedrockDtos.ConverseRequest body,
                                                 HttpServletRequest http) {
        authGuard.check(Provider.BEDROCK, http);
        long started = System.nanoTime();
        MockRequest request = toMockRequest(modelId, body, "converse", false);
        MockCompletion completion = engine.complete(request, MockOverrides.from(http));

        List<BedrockDtos.ContentBlock> content = new ArrayList<>();
        if (completion.text() != null && !completion.text().isEmpty()) {
            content.add(BedrockDtos.ContentBlock.text(completion.text()));
        }
        for (ToolCall call : completion.toolCalls()) {
            content.add(BedrockDtos.ContentBlock.toolUse(new BedrockDtos.ToolUseBlock(
                    Ids.bedrockToolUse(), call.name(), parseArguments(call.arguments()))));
        }
        return new BedrockDtos.ConverseResponse(
                new BedrockDtos.ConverseOutput(new BedrockDtos.Message("assistant", content)),
                stopReason(completion.finishReason()), usage(completion),
                new BedrockDtos.ConverseMetrics(elapsedMs(started)), null);
    }

    @PostMapping("/model/{modelId}/converse-stream")
    public ResponseEntity<Void> converseStream(@PathVariable String modelId,
                                               @RequestBody BedrockDtos.ConverseRequest body,
                                               HttpServletRequest http,
                                               HttpServletResponse response) throws IOException {
        authGuard.check(Provider.BEDROCK, http);
        long started = System.nanoTime();
        MockRequest request = toMockRequest(modelId, body, "converse-stream", true);
        MockCompletion completion = engine.complete(request, MockOverrides.from(http));

        OutputStream out = StreamResponse.begin(response, EVENT_STREAM_CONTENT_TYPE);
        long delay = properties.getStream().getDelayMs();

        event(out, "messageStart", new BedrockDtos.MessageStartEvent("assistant"));

        int index = 0;
        if (completion.text() != null && !completion.text().isEmpty()) {
            event(out, "contentBlockStart", new BedrockDtos.ContentBlockStartEvent(index, null));
            for (String piece : TextChunker.chunk(completion.text(),
                    properties.getStream().getWordsPerChunk())) {
                StreamResponse.pause(delay);
                event(out, "contentBlockDelta", new BedrockDtos.ContentBlockDeltaEvent(index,
                        new BedrockDtos.ContentBlockDelta(piece, null)));
            }
            event(out, "contentBlockStop", new BedrockDtos.ContentBlockStopEvent(index));
            index++;
        }
        for (ToolCall call : completion.toolCalls()) {
            event(out, "contentBlockStart", new BedrockDtos.ContentBlockStartEvent(index,
                    new BedrockDtos.ContentBlockStart(
                            new BedrockDtos.ToolUseStart(Ids.bedrockToolUse(), call.name()))));
            StreamResponse.pause(delay);
            event(out, "contentBlockDelta", new BedrockDtos.ContentBlockDeltaEvent(index,
                    new BedrockDtos.ContentBlockDelta(null,
                            new BedrockDtos.ToolUseDelta(call.arguments()))));
            event(out, "contentBlockStop", new BedrockDtos.ContentBlockStopEvent(index));
            index++;
        }

        event(out, "messageStop",
                new BedrockDtos.MessageStopEvent(stopReason(completion.finishReason()), null));
        event(out, "metadata", new BedrockDtos.MetadataEvent(usage(completion),
                new BedrockDtos.ConverseMetrics(elapsedMs(started))));
        return null;
    }

    // --- InvokeModel -----------------------------------------------------------------

    @PostMapping("/model/{modelId}/invoke")
    public JsonNode invoke(@PathVariable String modelId, @RequestBody JsonNode body,
                           HttpServletRequest http) {
        authGuard.check(Provider.BEDROCK, http);
        BedrockFamily family = BedrockFamily.of(modelId);
        MockRequest request = nativeToMockRequest(modelId, family, body, "invoke", false);
        MockCompletion completion = engine.complete(request, MockOverrides.from(http));
        return nativeResponse(family, modelId, completion);
    }

    @PostMapping("/model/{modelId}/invoke-with-response-stream")
    public ResponseEntity<Void> invokeStream(@PathVariable String modelId, @RequestBody JsonNode body,
                                             HttpServletRequest http, HttpServletResponse response)
            throws IOException {
        authGuard.check(Provider.BEDROCK, http);
        BedrockFamily family = BedrockFamily.of(modelId);
        MockRequest request = nativeToMockRequest(modelId, family, body,
                "invoke-with-response-stream", true);
        MockCompletion completion = engine.complete(request, MockOverrides.from(http));

        OutputStream out = StreamResponse.begin(response, EVENT_STREAM_CONTENT_TYPE);
        long delay = properties.getStream().getDelayMs();
        // Every native chunk is wrapped as {"bytes": base64(...)} inside a "chunk" event.
        for (Object chunk : nativeStreamChunks(family, modelId, completion)) {
            StreamResponse.pause(delay);
            Map<String, String> wrapper = Map.of("bytes", Base64.getEncoder()
                    .encodeToString(mapper.writeValueAsString(chunk).getBytes(StandardCharsets.UTF_8)));
            event(out, "chunk", wrapper);
        }
        return null;
    }

    private List<Object> nativeStreamChunks(BedrockFamily family, String modelId,
                                            MockCompletion completion) {
        List<String> pieces = TextChunker.chunk(completion.text(),
                properties.getStream().getWordsPerChunk());
        List<Object> chunks = new ArrayList<>();
        switch (family) {
            case TITAN -> {
                for (int i = 0; i < pieces.size(); i++) {
                    boolean last = i == pieces.size() - 1;
                    Map<String, Object> chunk = new LinkedHashMap<>();
                    chunk.put("outputText", pieces.get(i));
                    chunk.put("index", 0);
                    if (last) {
                        chunk.put("completionReason", titanCompletionReason(completion.finishReason()));
                        chunk.put("inputTextTokenCount", completion.usage().inputTokens());
                        chunk.put("totalOutputTextTokenCount", completion.usage().outputTokens());
                    }
                    chunks.add(chunk);
                }
            }
            case LLAMA -> {
                for (int i = 0; i < pieces.size(); i++) {
                    boolean last = i == pieces.size() - 1;
                    Map<String, Object> chunk = new LinkedHashMap<>();
                    chunk.put("generation", pieces.get(i));
                    if (last) {
                        chunk.put("stop_reason", llamaStopReason(completion.finishReason()));
                        chunk.put("prompt_token_count", completion.usage().inputTokens());
                        chunk.put("generation_token_count", completion.usage().outputTokens());
                    }
                    chunks.add(chunk);
                }
            }
            case NOVA -> {
                chunks.add(Map.of("messageStart", Map.of("role", "assistant")));
                for (String piece : pieces) {
                    chunks.add(Map.of("contentBlockDelta",
                            Map.of("contentBlockIndex", 0, "delta", Map.of("text", piece))));
                }
                chunks.add(Map.of("contentBlockStop", Map.of("contentBlockIndex", 0)));
                chunks.add(Map.of("messageStop",
                        Map.of("stopReason", stopReason(completion.finishReason()))));
                chunks.add(Map.of("metadata", Map.of("usage", usageMap(completion))));
            }
            case ANTHROPIC -> {
                // Anthropic on Bedrock streams the same event objects as the direct API.
                chunks.add(Map.of("type", "message_start", "message", Map.of(
                        "id", "msg_" + completion.id(), "type", "message", "role", "assistant",
                        "model", modelId, "content", List.of(),
                        "usage", Map.of("input_tokens", completion.usage().inputTokens(),
                                "output_tokens", 0))));
                chunks.add(Map.of("type", "content_block_start", "index", 0,
                        "content_block", Map.of("type", "text", "text", "")));
                for (String piece : pieces) {
                    chunks.add(Map.of("type", "content_block_delta", "index", 0,
                            "delta", Map.of("type", "text_delta", "text", piece)));
                }
                chunks.add(Map.of("type", "content_block_stop", "index", 0));
                chunks.add(Map.of("type", "message_delta",
                        "delta", Map.of("stop_reason",
                                AnthropicWire.stopReason(completion.finishReason())),
                        "usage", Map.of("output_tokens", completion.usage().outputTokens())));
                chunks.add(Map.of("type", "message_stop"));
            }
        }
        return chunks;
    }

    // --- native (InvokeModel) payload mapping ----------------------------------------

    private MockRequest nativeToMockRequest(String modelId, BedrockFamily family, JsonNode body,
                                            String endpoint, boolean stream) {
        if (body == null || body.isNull()) {
            throw new MockApiException(400, "invalid_request", "Request body is required");
        }
        MockRequest.Builder builder = MockRequest.builder(Provider.BEDROCK, endpoint)
                .model(modelId)
                .stream(stream);
        switch (family) {
            case TITAN -> {
                builder.message(ChatRole.USER, text(body, "inputText"));
                JsonNode config = body.get("textGenerationConfig");
                if (config != null) {
                    builder.maxTokens(intOrNull(config, "maxTokenCount"))
                            .temperature(doubleOrNull(config, "temperature"))
                            .topP(doubleOrNull(config, "topP"));
                }
            }
            case LLAMA -> builder.message(ChatRole.USER, text(body, "prompt"))
                    .maxTokens(intOrNull(body, "max_gen_len"))
                    .temperature(doubleOrNull(body, "temperature"))
                    .topP(doubleOrNull(body, "top_p"));
            case NOVA -> {
                appendSystemBlocks(builder, body.get("system"));
                appendConverseMessages(builder, body.get("messages"));
                JsonNode config = body.get("inferenceConfig");
                if (config != null) {
                    builder.maxTokens(intOrNull(config, "maxTokens"))
                            .temperature(doubleOrNull(config, "temperature"))
                            .topP(doubleOrNull(config, "topP"));
                }
            }
            case ANTHROPIC -> {
                builder.message(ChatRole.SYSTEM, flattenAnthropicSystem(body.get("system")));
                JsonNode messages = body.get("messages");
                if (messages != null && messages.isArray()) {
                    for (JsonNode message : messages) {
                        builder.message(ChatRole.from(text(message, "role")),
                                com.github.llmmock.provider.common.JsonText
                                        .flatten(message.get("content")));
                    }
                }
                builder.maxTokens(intOrNull(body, "max_tokens"))
                        .temperature(doubleOrNull(body, "temperature"))
                        .topP(doubleOrNull(body, "top_p"));
            }
        }
        return builder.build();
    }

    private JsonNode nativeResponse(BedrockFamily family, String modelId, MockCompletion completion) {
        Map<String, Object> payload = switch (family) {
            case TITAN -> Map.of(
                    "inputTextTokenCount", completion.usage().inputTokens(),
                    "results", List.of(new LinkedHashMap<>(Map.of(
                            "tokenCount", completion.usage().outputTokens(),
                            "outputText", completion.text(),
                            "completionReason", titanCompletionReason(completion.finishReason())))));
            case LLAMA -> Map.of(
                    "generation", completion.text(),
                    "prompt_token_count", completion.usage().inputTokens(),
                    "generation_token_count", completion.usage().outputTokens(),
                    "stop_reason", llamaStopReason(completion.finishReason()));
            case NOVA -> Map.of(
                    "output", Map.of("message", Map.of("role", "assistant",
                            "content", List.of(Map.of("text", completion.text())))),
                    "stopReason", stopReason(completion.finishReason()),
                    "usage", usageMap(completion));
            case ANTHROPIC -> anthropicNativePayload(modelId, completion);
        };
        return mapper.valueToTree(payload);
    }

    private Map<String, Object> anthropicNativePayload(String modelId, MockCompletion completion) {
        List<Object> content = new ArrayList<>();
        if (completion.text() != null && !completion.text().isEmpty()) {
            content.add(Map.of("type", "text", "text", completion.text()));
        }
        for (ToolCall call : completion.toolCalls()) {
            content.add(Map.of("type", "tool_use", "id", Ids.anthropicToolUse(),
                    "name", call.name(), "input", parseArguments(call.arguments())));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "msg_" + completion.id());
        payload.put("type", "message");
        payload.put("role", "assistant");
        payload.put("model", modelId);
        payload.put("content", content);
        payload.put("stop_reason", AnthropicWire.stopReason(completion.finishReason()));
        payload.put("stop_sequence", null);
        payload.put("usage", Map.of("input_tokens", completion.usage().inputTokens(),
                "output_tokens", completion.usage().outputTokens()));
        return payload;
    }

    // --- Converse payload mapping ----------------------------------------------------

    private MockRequest toMockRequest(String modelId, BedrockDtos.ConverseRequest body,
                                      String endpoint, boolean stream) {
        if (modelId == null || modelId.isBlank()) {
            throw new MockApiException(400, "invalid_request", "modelId is required");
        }
        if (body == null || body.messages() == null || body.messages().isEmpty()) {
            throw new MockApiException(400, "invalid_request",
                    "The messages field must not be empty");
        }
        MockRequest.Builder builder = MockRequest.builder(Provider.BEDROCK, endpoint)
                .model(modelId)
                .stream(stream);
        if (body.system() != null) {
            for (BedrockDtos.SystemContentBlock block : body.system()) {
                builder.message(ChatRole.SYSTEM, block.text());
            }
        }
        for (BedrockDtos.Message message : body.messages()) {
            builder.message(ChatRole.from(message.role()), flatten(message.content()));
        }
        BedrockDtos.InferenceConfiguration config = body.inferenceConfig();
        if (config != null) {
            builder.maxTokens(config.maxTokens())
                    .temperature(config.temperature())
                    .topP(config.topP())
                    .stopSequences(config.stopSequences());
        }
        if (body.toolConfig() != null && body.toolConfig().tools() != null) {
            for (BedrockDtos.Tool tool : body.toolConfig().tools()) {
                if (tool.toolSpec() != null) {
                    builder.tool(new ToolSpec(tool.toolSpec().name(),
                            tool.toolSpec().description(), null));
                }
            }
        }
        return builder.build();
    }

    private String flatten(List<BedrockDtos.ContentBlock> content) {
        if (content == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (BedrockDtos.ContentBlock block : content) {
            if (block != null && block.text() != null && !block.text().isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(block.text());
            }
        }
        return sb.toString();
    }

    private void appendSystemBlocks(MockRequest.Builder builder, JsonNode system) {
        if (system != null && system.isArray()) {
            for (JsonNode block : system) {
                builder.message(ChatRole.SYSTEM, text(block, "text"));
            }
        }
    }

    private void appendConverseMessages(MockRequest.Builder builder, JsonNode messages) {
        if (messages == null || !messages.isArray()) {
            return;
        }
        for (JsonNode message : messages) {
            StringBuilder sb = new StringBuilder();
            JsonNode content = message.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode block : content) {
                    String value = text(block, "text");
                    if (!value.isEmpty()) {
                        if (!sb.isEmpty()) {
                            sb.append('\n');
                        }
                        sb.append(value);
                    }
                }
            }
            builder.message(ChatRole.from(text(message, "role")), sb.toString());
        }
    }

    private String flattenAnthropicSystem(JsonNode system) {
        return com.github.llmmock.provider.common.JsonText.flatten(system);
    }

    // --- helpers ---------------------------------------------------------------------

    private void event(OutputStream out, String eventType, Object payload) throws IOException {
        EventStreamEncoder.writeEvent(out, eventType,
                mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
    }

    private BedrockDtos.TokenUsage usage(MockCompletion completion) {
        return new BedrockDtos.TokenUsage(completion.usage().inputTokens(),
                completion.usage().outputTokens(), completion.usage().totalTokens());
    }

    private Map<String, Object> usageMap(MockCompletion completion) {
        return Map.of("inputTokens", completion.usage().inputTokens(),
                "outputTokens", completion.usage().outputTokens(),
                "totalTokens", completion.usage().totalTokens());
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(1L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private JsonNode parseArguments(String arguments) {
        try {
            JsonNode node = mapper.readTree(arguments == null ? "{}" : arguments);
            return node.isObject() ? node : mapper.createObjectNode().set("value", node);
        } catch (RuntimeException ex) {
            return mapper.createObjectNode().put("raw", arguments);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString("");
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isNumber() ? null : value.asDouble();
    }

    static String stopReason(FinishReason reason) {
        return switch (reason) {
            case LENGTH -> "max_tokens";
            case TOOL_USE -> "tool_use";
            case CONTENT_FILTER -> "content_filtered";
            case STOP_SEQUENCE -> "stop_sequence";
            case STOP -> "end_turn";
        };
    }

    private static String titanCompletionReason(FinishReason reason) {
        return switch (reason) {
            case LENGTH -> "LENGTH";
            case CONTENT_FILTER -> "CONTENT_FILTERED";
            case STOP, STOP_SEQUENCE, TOOL_USE -> "FINISH";
        };
    }

    private static String llamaStopReason(FinishReason reason) {
        return reason == FinishReason.LENGTH ? "length" : "stop";
    }
}
