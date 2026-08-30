package com.github.llmmock.provider.anthropic;

import java.util.UUID;

import com.github.llmmock.core.FinishReason;

/**
 * Anthropic wire vocabulary shared by the direct Messages adapter and the Bedrock adapter,
 * which serves the identical payload shape for {@code anthropic.*} models.
 */
public final class AnthropicWire {

    private AnthropicWire() {
    }

    public static String stopReason(FinishReason reason) {
        return switch (reason) {
            case LENGTH -> "max_tokens";
            case TOOL_USE -> "tool_use";
            case CONTENT_FILTER -> "refusal";
            case STOP_SEQUENCE -> "stop_sequence";
            case STOP -> "end_turn";
        };
    }

    public static String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
