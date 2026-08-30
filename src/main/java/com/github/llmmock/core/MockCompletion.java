package com.github.llmmock.core;

import java.util.List;

/**
 * Provider neutral completion produced by the engine. Adapters render this into their
 * own response envelope.
 */
public record MockCompletion(
        String id,
        String model,
        String text,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        Usage usage,
        String matchedStub) {

    public MockCompletion {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
