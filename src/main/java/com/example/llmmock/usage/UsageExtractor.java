package com.example.llmmock.usage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.llmmock.core.Provider;
import com.example.llmmock.proxy.EventStreamFrames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the model name and token counts back out of a provider response.
 *
 * <p>In proxy mode the answer is produced upstream, so the only place those numbers exist
 * is the response body. Every provider spells them differently, and streaming responses
 * split them across events, so each format is handled explicitly rather than guessed at.
 */
@Component
public class UsageExtractor {

    private static final Logger log = LoggerFactory.getLogger(UsageExtractor.class);

    /** Gemini and Bedrock carry the model in the URL rather than in the response. */
    private static final Pattern GEMINI_MODEL = Pattern.compile("/models/([^:/?]+)");
    private static final Pattern BEDROCK_MODEL = Pattern.compile("/model/([^/]+)/");

    private final ObjectMapper mapper;

    public UsageExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** What a single call consumed, once the response has been read. */
    public record Extracted(String model, TokenUsage usage) {
    }

    public Optional<Extracted> extract(Provider provider, String path, String contentType,
                                       byte[] body) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        try {
            return switch (provider) {
                case OPENAI -> openAi(path, contentType, body);
                case ANTHROPIC -> anthropic(path, contentType, body);
                case GEMINI -> gemini(path, contentType, body);
                case BEDROCK -> bedrock(path, contentType, body);
                case ANY -> Optional.empty();
            };
        } catch (RuntimeException ex) {
            // Usage accounting must never break the call that produced it.
            log.debug("Could not extract usage for {} {}: {}", provider, path, ex.toString());
            return Optional.empty();
        }
    }

    // --- OpenAI ------------------------------------------------------------------------

    private Optional<Extracted> openAi(String path, String contentType, byte[] body) {
        if (isSse(contentType)) {
            // Only present when the caller asked for stream_options.include_usage.
            String model = null;
            for (String payload : sseData(body)) {
                if ("[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode chunk = mapper.readTree(payload);
                model = text(chunk, "model", model);
                JsonNode usage = chunk.get("usage");
                if (usage != null && !usage.isNull()) {
                    return Optional.of(new Extracted(model, openAiUsage(usage)));
                }
            }
            return Optional.empty();
        }
        JsonNode response = mapper.readTree(body);
        JsonNode usage = response.get("usage");
        if (usage == null || usage.isNull()) {
            return Optional.empty();
        }
        return Optional.of(new Extracted(text(response, "model", null), openAiUsage(usage)));
    }

    private TokenUsage openAiUsage(JsonNode usage) {
        int input = number(usage, "prompt_tokens");
        int output = number(usage, "completion_tokens");
        int total = usage.get("total_tokens") != null ? number(usage, "total_tokens")
                : input + output;
        JsonNode details = usage.get("prompt_tokens_details");
        int cached = details == null ? 0 : number(details, "cached_tokens");
        return new TokenUsage(input, output, total, cached, 0);
    }

    // --- Anthropic ---------------------------------------------------------------------

    private Optional<Extracted> anthropic(String path, String contentType, byte[] body) {
        if (path != null && path.contains("count_tokens")) {
            // Counting tokens is a separate, far cheaper endpoint; it is not a completion.
            return Optional.empty();
        }
        if (isSse(contentType)) {
            // Input tokens arrive in message_start, output tokens only in message_delta.
            String model = null;
            int input = 0;
            int output = 0;
            int cacheRead = 0;
            int cacheWrite = 0;
            for (String payload : sseData(body)) {
                JsonNode event = mapper.readTree(payload);
                String type = text(event, "type", "");
                if ("message_start".equals(type)) {
                    JsonNode message = event.get("message");
                    if (message != null) {
                        model = text(message, "model", model);
                        JsonNode usage = message.get("usage");
                        if (usage != null) {
                            input = number(usage, "input_tokens");
                            cacheRead = number(usage, "cache_read_input_tokens");
                            cacheWrite = number(usage, "cache_creation_input_tokens");
                        }
                    }
                } else if ("message_delta".equals(type)) {
                    JsonNode usage = event.get("usage");
                    if (usage != null) {
                        output = number(usage, "output_tokens");
                    }
                }
            }
            if (input == 0 && output == 0) {
                return Optional.empty();
            }
            return Optional.of(new Extracted(model,
                    TokenUsage.of(input, output).withCache(cacheRead, cacheWrite)));
        }
        JsonNode response = mapper.readTree(body);
        JsonNode usage = response.get("usage");
        if (usage == null || usage.isNull()) {
            return Optional.empty();
        }
        return Optional.of(new Extracted(text(response, "model", null),
                TokenUsage.of(number(usage, "input_tokens"), number(usage, "output_tokens"))
                        .withCache(number(usage, "cache_read_input_tokens"),
                                number(usage, "cache_creation_input_tokens"))));
    }

    // --- Gemini ------------------------------------------------------------------------

    private Optional<Extracted> gemini(String path, String contentType, byte[] body) {
        String pathModel = firstGroup(GEMINI_MODEL, path);
        if (isSse(contentType)) {
            // Only the last chunk carries usageMetadata, so the last one wins.
            Optional<Extracted> latest = Optional.empty();
            for (String payload : sseData(body)) {
                Optional<Extracted> candidate =
                        geminiObject(mapper.readTree(payload), pathModel);
                if (candidate.isPresent()) {
                    latest = candidate;
                }
            }
            return latest;
        }
        JsonNode response = mapper.readTree(body);
        if (response.isArray()) {
            // The non-SSE streaming form is a JSON array of the same objects.
            Optional<Extracted> latest = Optional.empty();
            for (JsonNode element : response) {
                Optional<Extracted> candidate = geminiObject(element, pathModel);
                if (candidate.isPresent()) {
                    latest = candidate;
                }
            }
            return latest;
        }
        return geminiObject(response, pathModel);
    }

    private Optional<Extracted> geminiObject(JsonNode response, String pathModel) {
        JsonNode usage = response.get("usageMetadata");
        if (usage == null || usage.isNull()) {
            return Optional.empty();
        }
        int input = number(usage, "promptTokenCount");
        int output = number(usage, "candidatesTokenCount");
        int total = usage.get("totalTokenCount") != null ? number(usage, "totalTokenCount")
                : input + output;
        return Optional.of(new Extracted(text(response, "modelVersion", pathModel),
                new TokenUsage(input, output, total,
                        number(usage, "cachedContentTokenCount"), 0)));
    }

    // --- Bedrock -----------------------------------------------------------------------

    private Optional<Extracted> bedrock(String path, String contentType, byte[] body) {
        String model = firstGroup(BEDROCK_MODEL, path);
        if (isEventStream(contentType)) {
            for (EventStreamFrames.Frame frame : EventStreamFrames.parse(body)) {
                if ("metadata".equals(frame.eventType())) {
                    JsonNode usage = mapper.readTree(frame.payload()).get("usage");
                    if (usage != null) {
                        return Optional.of(new Extracted(model, bedrockUsage(usage)));
                    }
                }
                if ("chunk".equals(frame.eventType())) {
                    // InvokeModelWithResponseStream wraps the model's native event.
                    JsonNode wrapper = mapper.readTree(frame.payload());
                    JsonNode encoded = wrapper.get("bytes");
                    if (encoded != null && encoded.isString()) {
                        Optional<Extracted> nested = nativeInvokeUsage(model, new String(
                                Base64.getDecoder().decode(encoded.asString()),
                                StandardCharsets.UTF_8));
                        if (nested.isPresent()) {
                            return nested;
                        }
                    }
                }
            }
            return Optional.empty();
        }
        JsonNode response = mapper.readTree(body);
        JsonNode usage = response.get("usage");
        if (usage != null && !usage.isNull() && usage.get("inputTokens") != null) {
            return Optional.of(new Extracted(model, bedrockUsage(usage)));
        }
        return nativeInvokeUsage(model, new String(body, StandardCharsets.UTF_8));
    }

    private TokenUsage bedrockUsage(JsonNode usage) {
        int input = number(usage, "inputTokens");
        int output = number(usage, "outputTokens");
        int total = usage.get("totalTokens") != null ? number(usage, "totalTokens") : input + output;
        return new TokenUsage(input, output, total, number(usage, "cacheReadInputTokens"),
                number(usage, "cacheWriteInputTokens"));
    }

    /** InvokeModel passes the model's own body through, so each family reports differently. */
    private Optional<Extracted> nativeInvokeUsage(String model, String json) {
        JsonNode response = mapper.readTree(json);

        JsonNode anthropic = response.get("usage");
        if (anthropic != null && anthropic.get("input_tokens") != null) {
            return Optional.of(new Extracted(model,
                    TokenUsage.of(number(anthropic, "input_tokens"),
                            number(anthropic, "output_tokens"))));
        }
        if (response.get("inputTextTokenCount") != null) {
            int output = 0;
            JsonNode results = response.get("results");
            if (results != null && results.isArray() && !results.isEmpty()) {
                output = number(results.get(0), "tokenCount");
            } else if (response.get("totalOutputTextTokenCount") != null) {
                output = number(response, "totalOutputTextTokenCount");
            }
            return Optional.of(new Extracted(model,
                    TokenUsage.of(number(response, "inputTextTokenCount"), output)));
        }
        if (response.get("prompt_token_count") != null) {
            return Optional.of(new Extracted(model,
                    TokenUsage.of(number(response, "prompt_token_count"),
                            number(response, "generation_token_count"))));
        }
        return Optional.empty();
    }

    // --- helpers -----------------------------------------------------------------------

    private static boolean isSse(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("text/event-stream");
    }

    private static boolean isEventStream(String contentType) {
        return contentType != null
                && contentType.toLowerCase().contains("vnd.amazon.eventstream");
    }

    /** The payload of every {@code data:} line, in order. */
    private static java.util.List<String> sseData(byte[] body) {
        java.util.List<String> payloads = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (trimmed.startsWith("data:")) {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(trimmed.substring("data:".length()).stripLeading());
            } else if (trimmed.isEmpty() && !current.isEmpty()) {
                payloads.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            payloads.add(current.toString());
        }
        return payloads;
    }

    private static String firstGroup(Pattern pattern, String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isNumber() ? 0 : value.asInt();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isString() ? fallback : value.asString();
    }
}
