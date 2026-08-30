package io.github.ykwyuta.llmmock.core;

/** Canonical message role. Provider specific spellings are normalised into this. */
public enum ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    public static ChatRole from(String raw) {
        if (raw == null) {
            return USER;
        }
        return switch (raw.toLowerCase()) {
            case "system", "developer" -> SYSTEM;
            case "assistant", "model" -> ASSISTANT;
            case "tool", "function" -> TOOL;
            default -> USER;
        };
    }
}
