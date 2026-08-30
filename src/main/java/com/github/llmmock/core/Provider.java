package com.github.llmmock.core;

/** The LLM wire protocol a request arrived on. {@link #ANY} is only valid on stub rules. */
public enum Provider {
    ANY,
    OPENAI,
    ANTHROPIC,
    GEMINI,
    BEDROCK;

    public boolean matches(Provider actual) {
        return this == ANY || this == actual;
    }
}
