package io.github.ykwyuta.llmmock.usage;

/** Token counts for a single call, normalised across the four providers' spellings. */
public record TokenUsage(int inputTokens, int outputTokens, int totalTokens,
                         int cacheReadTokens, int cacheWriteTokens) {

    public static TokenUsage of(int inputTokens, int outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens, 0, 0);
    }

    public TokenUsage withCache(int cacheReadTokens, int cacheWriteTokens) {
        return new TokenUsage(inputTokens, outputTokens, totalTokens, cacheReadTokens,
                cacheWriteTokens);
    }

    public boolean isEmpty() {
        return inputTokens == 0 && outputTokens == 0 && totalTokens == 0
                && cacheReadTokens == 0 && cacheWriteTokens == 0;
    }
}
