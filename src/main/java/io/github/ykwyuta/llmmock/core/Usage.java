package io.github.ykwyuta.llmmock.core;

public record Usage(int inputTokens, int outputTokens) {

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
