package com.example.llmmock.core;

import java.util.UUID;

/** Identifier helpers shaped like the ones the real providers hand out. */
public final class Ids {

    private Ids() {
    }

    public static String hex(int length) {
        StringBuilder raw = new StringBuilder();
        while (raw.length() < length) {
            raw.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return raw.substring(0, length);
    }

    public static String anthropicToolUse() {
        return "toolu_" + hex(24);
    }

    public static String openAiToolCall() {
        return "call_" + hex(24);
    }

    public static String bedrockToolUse() {
        return "tooluse_" + hex(22);
    }
}
