package com.example.llmmock.core;

/** A single conversation turn flattened to plain text. */
public record ChatMessage(ChatRole role, String text) {

    public static ChatMessage of(ChatRole role, String text) {
        return new ChatMessage(role, text == null ? "" : text);
    }
}
