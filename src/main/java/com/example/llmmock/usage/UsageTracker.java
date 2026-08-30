package com.example.llmmock.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.llmmock.config.LlmMockProperties;
import com.example.llmmock.core.Provider;

/**
 * Records what each call consumed and reports it as a cost summary.
 *
 * <p>Writes run in their own transaction for the same reason the request log does: a
 * simulated failure rolls the caller's transaction back, and the accounting of what led up
 * to it should survive that.
 *
 * <p>Both the pruning and the summary do their work in the database. The table is allowed
 * to hold a million rows, and neither loading every row on each insert nor loading every
 * row to build a report survives that.
 */
@Component
public class UsageTracker {

    private final UsageRepository repository;
    private final PricingTable pricing;
    private final LlmMockProperties properties;

    public UsageTracker(UsageRepository repository, PricingTable pricing,
                        LlmMockProperties properties) {
        this.repository = repository;
        this.pricing = pricing;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Provider provider, String model, String endpoint, boolean streaming,
                       UsageSource source, TokenUsage usage) {
        if (!properties.getCost().isEnabled() || usage == null || usage.isEmpty()) {
            return;
        }
        UsageRecord record = new UsageRecord();
        record.setProvider(provider);
        record.setModel(model);
        record.setEndpoint(endpoint);
        record.setStreaming(streaming);
        record.setSource(source);
        record.setInputTokens(usage.inputTokens());
        record.setOutputTokens(usage.outputTokens());
        record.setTotalTokens(usage.totalTokens());
        record.setCacheReadTokens(usage.cacheReadTokens());
        record.setCacheWriteTokens(usage.cacheWriteTokens());
        record.setEstimatedCost(pricing.estimate(model, usage));
        repository.save(record);
        prune();
    }

    /**
     * Drops the oldest rows once the table exceeds its cap.
     *
     * <p>Row count is derived from the id range instead of {@code count(*)}: ids are
     * generated in order and only ever removed from the front by this method, so the range
     * is exact, and both bounds are index lookups. The removal is one bulk delete rather
     * than a row-by-row pass over everything that has to go.
     */
    private void prune() {
        int max = properties.getCost().getMaxEntries();
        if (max <= 0) {
            return;
        }
        Long highest = repository.highestId();
        Long lowest = repository.lowestId();
        if (highest == null || lowest == null) {
            return;
        }
        if (highest - lowest + 1 <= max) {
            return;
        }
        repository.deleteUpToId(highest - max);
    }

    @Transactional(readOnly = true)
    public UsageSummary summarise(Provider providerFilter, UsageSource sourceFilter) {
        List<UsageSummary.ModelRow> rows = new ArrayList<>();
        TreeSet<String> unpriced = new TreeSet<>();

        for (Object[] row : repository.aggregateByModel(providerFilter, sourceFilter)) {
            Provider provider = (Provider) row[0];
            String model = (String) row[1];
            boolean priced = pricing.isPriced(model);
            if (!priced && model != null) {
                unpriced.add(model);
            }
            rows.add(new UsageSummary.ModelRow(provider, model, count(row[2]),
                    count(row[3]), count(row[4]), count(row[5]), count(row[6]), count(row[7]),
                    // An unpriced model reports no cost rather than a misleading zero.
                    priced ? (BigDecimal) row[8] : null, priced));
        }
        rows.sort(Comparator.comparing(UsageSummary.ModelRow::totalTokens).reversed());

        long requests = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        long totalTokens = 0;
        BigDecimal cost = null;
        BigDecimal upstreamCost = null;
        BigDecimal cacheSavings = null;
        long upstreamRequests = 0;
        long cacheHits = 0;

        for (Object[] row : repository.aggregateBySource(providerFilter, sourceFilter)) {
            UsageSource source = (UsageSource) row[0];
            long sourceRequests = count(row[1]);
            BigDecimal sourceCost = (BigDecimal) row[5];

            requests += sourceRequests;
            inputTokens += count(row[2]);
            outputTokens += count(row[3]);
            totalTokens += count(row[4]);
            cost = add(cost, sourceCost);

            if (source == UsageSource.UPSTREAM) {
                upstreamRequests = sourceRequests;
                upstreamCost = sourceCost;
            } else if (source == UsageSource.CACHE) {
                cacheHits = sourceRequests;
                cacheSavings = sourceCost;
            }
        }

        UsageSummary.Totals totals = new UsageSummary.Totals(requests, inputTokens, outputTokens,
                totalTokens, cost, upstreamCost, cacheSavings, upstreamRequests, cacheHits);
        return new UsageSummary(properties.getCost().getCurrency(), rows, totals,
                new ArrayList<>(unpriced));
    }

    @Transactional
    public void deleteAll() {
        repository.deleteAll();
    }

    private static long count(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** Null stays null so "nothing was priced" never reads as "it was free". */
    private static BigDecimal add(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : left.add(right);
    }
}
