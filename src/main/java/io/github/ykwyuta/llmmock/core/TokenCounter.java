package io.github.ykwyuta.llmmock.core;

import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for a real tokenizer: roughly four characters per token, plus a
 * small per-message overhead. Values are stable across runs so tests can assert on them.
 */
@Component
public class TokenCounter {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int PER_MESSAGE_OVERHEAD = 3;

    public int countText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil((double) text.length() / CHARS_PER_TOKEN);
    }

    public int countRequest(MockRequest request) {
        int total = 0;
        for (ChatMessage message : request.messages()) {
            total += PER_MESSAGE_OVERHEAD + countText(message.text());
        }
        for (ToolSpec tool : request.tools()) {
            total += countText(tool.name()) + countText(tool.description());
        }
        return total;
    }
}
