package com.github.llmmock.core;

public record Usage(int inputTokens, int outputTokens) {

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
