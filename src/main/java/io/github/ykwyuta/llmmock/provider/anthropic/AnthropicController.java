package io.github.ykwyuta.llmmock.provider.anthropic;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import io.github.ykwyuta.llmmock.config.LlmMockProperties;
import io.github.ykwyuta.llmmock.config.ProviderApi;
import io.github.ykwyuta.llmmock.core.ChatRole;
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
import io.github.ykwyuta.llmmock.provider.common.JsonText;
import io.github.ykwyuta.llmmock.provider.common.SseWriter;
import io.github.ykwyuta.llmmock.provider.common.StreamResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Anthropic-compatible surface. Point an Anthropic SDK's base URL at
 * {@code http://localhost:8080/anthropic}.
 *
 * <p>The {@code anthropic-version} header is accepted but not enforced, so a client that
 * omits it still gets a usable answer from the mock.
 */
@ProviderApi(Provider.ANTHROPIC)
@RequestMapping("/v1")
public class AnthropicController {

    private final MockEngine engine;
    private final ObjectMapper mapper;
    private final AuthGuard authGuard;
    private final LlmMockProperties properties;
    private final TokenCounter tokenCounter;

    public AnthropicController(MockEngine engine, ObjectMapper mapper, AuthGuard authGuard,
                               LlmMockProperties properties, TokenCounter tokenCounter) {
        this.engine = engine;
        this.mapper = mapper;
        this.authGuard = authGuard;
        this.properties = properties;
        this.tokenCounter = tokenCounter;
    }

    // --- messages --------------------------------------------------------------------

    @PostMapping("/messages")
    public ResponseEntity<AnthropicDtos.MessageResponse> messages(
            @RequestBody AnthropicDtos.MessagesRequest body,
            HttpServletRequest http, HttpServletResponse response) throws IOException {
        authGuard.check(Provider.ANTHROPIC, http);
        MockRequest request = toMockRequest(body, "messages");
        MockCompletion completion = engine.complete(request, MockOverrides.from(http));

        if (!request.stream()) {
            return ResponseEntity.ok(toResponse(request, completion));
        }
        writeStream(request, completion,
                StreamResponse.begin(response, MediaType.TEXT_EVENT_STREAM_VALUE));
        return null;
    }

    private MockRequest toMockRequest(AnthropicDtos.MessagesRequest body, String endpoint) {
        if (body == null || body.model() == null || body.model().isBlank()) {
            throw MockApiException.invalidRequest("model: Field required");
        }
        if (body.messages() == null || body.messages().isEmpty()) {
            throw MockApiException.invalidRequest("messages: at least one message is required");
        }
        // max_tokens is mandatory on the real Messages API, unlike every other provider.
        if ("messages".equals(endpoint) && body.maxTokens() == null) {
            throw MockApiException.invalidRequest("max_tokens: Field required");
        }
        MockRequest.Builder builder = MockRequest.builder(Provider.ANTHROPIC, endpoint)
                .model(body.model())
                .maxTokens(body.maxTokens())
                .temperature(body.temperature())
                .topP(body.topP())
                .stopSequences(body.stopSequences())
                .stream(Boolean.TRUE.equals(body.stream()))
                .message(ChatRole.SYSTEM, JsonText.flatten(body.system()));
        for (AnthropicDtos.Message message : body.messages()) {
            builder.message(ChatRole.from(message.role()), JsonText.flatten(message.content()));
        }
        if (body.tools() != null) {
            for (AnthropicDtos.Tool tool : body.tools()) {
                builder.tool(new ToolSpec(tool.name(), tool.description(), null));
            }
        }
        return builder.build();
    }

    private AnthropicDtos.MessageResponse toResponse(MockRequest request, MockCompletion completion) {
        List<AnthropicDtos.ContentBlock> content = new ArrayList<>();
        if (completion.text() != null && !completion.text().isEmpty()) {
            content.add(AnthropicDtos.ContentBlock.text(completion.text()));
        }
        for (ToolCall call : completion.toolCalls()) {
            content.add(AnthropicDtos.ContentBlock.toolUse(Ids.anthropicToolUse(), call.name(),
                    parseArguments(call.arguments())));
        }
        String stopSequence = completion.finishReason() == FinishReason.STOP_SEQUENCE
                && !request.stopSequences().isEmpty() ? request.stopSequences().get(0) : null;
        return new AnthropicDtos.MessageResponse("msg_" + completion.id(), "message", "assistant",
                request.model(), content, AnthropicWire.stopReason(completion.finishReason()), stopSequence,
                usage(completion));
    }

    private void writeStream(MockRequest request, MockCompletion completion, OutputStream out)
            throws IOException {
        SseWriter sse = new SseWriter(out, mapper);
        String id = "msg_" + completion.id();
        long delay = properties.getStream().getDelayMs();

        // message_start carries the shell of the message with empty content; the input token
        // count is final here, the output count is only known at message_delta.
        AnthropicDtos.MessageResponse shell = new AnthropicDtos.MessageResponse(id, "message",
                "assistant", request.model(), List.of(), null, null,
                new AnthropicDtos.Usage(completion.usage().inputTokens(), 0, 0, 0));
        sse.event("message_start", new AnthropicDtos.MessageStartEvent("message_start", shell));
        sse.event("ping", new AnthropicDtos.SimpleEvent("ping"));

        int index = 0;
        if (completion.text() != null && !completion.text().isEmpty()) {
            sse.event("content_block_start", new AnthropicDtos.ContentBlockStartEvent(
                    "content_block_start", index, AnthropicDtos.ContentBlock.text("")));
            for (String piece : TextChunker.chunk(completion.text(),
                    properties.getStream().getWordsPerChunk())) {
                StreamResponse.pause(delay);
                sse.event("content_block_delta", new AnthropicDtos.ContentBlockDeltaEvent(
                        "content_block_delta", index,
                        new AnthropicDtos.Delta("text_delta", piece, null)));
            }
            sse.event("content_block_stop",
                    new AnthropicDtos.ContentBlockStopEvent("content_block_stop", index));
            index++;
        }

        for (ToolCall call : completion.toolCalls()) {
            // A tool_use block opens with empty input and its JSON arrives as input_json_delta.
            sse.event("content_block_start", new AnthropicDtos.ContentBlockStartEvent(
                    "content_block_start", index,
                    AnthropicDtos.ContentBlock.toolUse(Ids.anthropicToolUse(), call.name(),
                            mapper.createObjectNode())));
            StreamResponse.pause(delay);
            sse.event("content_block_delta", new AnthropicDtos.ContentBlockDeltaEvent(
                    "content_block_delta", index,
                    new AnthropicDtos.Delta("input_json_delta", null, call.arguments())));
            sse.event("content_block_stop",
                    new AnthropicDtos.ContentBlockStopEvent("content_block_stop", index));
            index++;
        }

        sse.event("message_delta", new AnthropicDtos.MessageDeltaEvent("message_delta",
                new AnthropicDtos.MessageDelta(AnthropicWire.stopReason(completion.finishReason()), null),
                new AnthropicDtos.Usage(null, completion.usage().outputTokens(), null, null)));
        sse.event("message_stop", new AnthropicDtos.SimpleEvent("message_stop"));
    }

    // --- token counting --------------------------------------------------------------

    @PostMapping("/messages/count_tokens")
    public AnthropicDtos.CountTokensResponse countTokens(
            @RequestBody AnthropicDtos.CountTokensRequest body, HttpServletRequest http) {
        authGuard.check(Provider.ANTHROPIC, http);
        AnthropicDtos.MessagesRequest asMessages = new AnthropicDtos.MessagesRequest(body.model(),
                null, body.messages(), body.system(), null, null, null, null, null, body.tools(),
                null, null);
        MockRequest request = toMockRequest(asMessages, "messages.count_tokens");
        int tokens = tokenCounter.countRequest(request);
        engine.recordSimple(Provider.ANTHROPIC, "messages.count_tokens", body.model(), 200,
                tokens + " input tokens");
        return new AnthropicDtos.CountTokensResponse(tokens);
    }

    // --- models ----------------------------------------------------------------------

    @GetMapping("/models")
    public AnthropicDtos.ModelList models() {
        List<String> ids = properties.getModels().getAnthropic();
        List<AnthropicDtos.Model> data = ids.stream()
                .map(id -> new AnthropicDtos.Model("model", id, displayName(id), "2025-01-01T00:00:00Z"))
                .toList();
        return new AnthropicDtos.ModelList(data, false,
                ids.isEmpty() ? null : ids.get(0),
                ids.isEmpty() ? null : ids.get(ids.size() - 1));
    }

    private String displayName(String id) {
        String[] parts = id.split("-");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // --- helpers ---------------------------------------------------------------------

    private AnthropicDtos.Usage usage(MockCompletion completion) {
        return new AnthropicDtos.Usage(completion.usage().inputTokens(),
                completion.usage().outputTokens(), 0, 0);
    }

    /** Anthropic sends tool input as a JSON object, not the JSON string the others use. */
    private JsonNode parseArguments(String arguments) {
        try {
            JsonNode node = mapper.readTree(arguments == null ? "{}" : arguments);
            return node.isObject() ? node : mapper.createObjectNode().set("value", node);
        } catch (RuntimeException ex) {
            return mapper.createObjectNode().put("raw", arguments);
        }
    }

}
