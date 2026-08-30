package com.github.llmmock.core;

/**
 * Canonical stop reason. Each provider adapter maps this onto its own vocabulary
 * ({@code stop}/{@code end_turn}/{@code STOP}/{@code end_turn} respectively).
 */
public enum FinishReason {
    STOP,
    LENGTH,
    TOOL_USE,
    CONTENT_FILTER,
    STOP_SEQUENCE;

    public static FinishReason from(String raw, FinishReason fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase()) {
            case "stop", "end_turn", "endturn" -> STOP;
            case "length", "max_tokens", "maxtokens" -> LENGTH;
            case "tool_use", "tool_calls", "tooluse", "toolcalls", "function_call" -> TOOL_USE;
            case "content_filter", "contentfilter", "safety", "guardrail_intervened" -> CONTENT_FILTER;
            case "stop_sequence", "stopsequence" -> STOP_SEQUENCE;
            default -> fallback;
        };
    }
}
