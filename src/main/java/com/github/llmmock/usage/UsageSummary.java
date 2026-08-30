package com.github.llmmock.usage;

import java.math.BigDecimal;
import java.util.List;

import com.github.llmmock.core.Provider;

/** Aggregated cost report. */
public record UsageSummary(
        String currency,
        List<ModelRow> byModel,
        Totals totals,
        /** Models seen with no price list entry: their tokens count, their cost cannot. */
        List<String> unpricedModels) {

    public record ModelRow(
            Provider provider,
            String model,
            long requests,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            BigDecimal cost,
            boolean priced) {
    }

    public record Totals(
            long requests,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            /** Cost of every recorded call, whatever its source. */
            BigDecimal cost,
            /** Cost of the calls that actually reached an upstream API. */
            BigDecimal upstreamCost,
            /** What the cache hits would have cost had they gone upstream. */
            BigDecimal cacheSavings,
            long upstreamRequests,
            long cacheHits) {
    }
}
