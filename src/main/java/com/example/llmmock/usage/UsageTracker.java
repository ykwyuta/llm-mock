package com.example.llmmock.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.data.domain.Sort;
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

    private void prune() {
        int max = properties.getCost().getMaxEntries();
        if (max <= 0) {
            return;
        }
        long count = repository.count();
        if (count <= max) {
            return;
        }
        repository.findAll(Sort.by("id").ascending()).stream()
                .limit(count - max)
                .forEach(repository::delete);
    }

    @Transactional(readOnly = true)
    public UsageSummary summarise(Provider providerFilter, UsageSource sourceFilter) {
        List<UsageRecord> records = repository.findAll().stream()
                .filter(record -> providerFilter == null || record.getProvider() == providerFilter)
                .filter(record -> sourceFilter == null || record.getSource() == sourceFilter)
                .toList();

        Map<String, List<UsageRecord>> grouped = new LinkedHashMap<>();
        for (UsageRecord record : records) {
            grouped.computeIfAbsent(record.getProvider() + "\n" + record.getModel(),
                    key -> new ArrayList<>()).add(record);
        }

        List<UsageSummary.ModelRow> rows = new ArrayList<>();
        TreeSet<String> unpriced = new TreeSet<>();
        for (List<UsageRecord> group : grouped.values()) {
            UsageRecord first = group.get(0);
            boolean priced = pricing.isPriced(first.getModel());
            if (!priced && first.getModel() != null) {
                unpriced.add(first.getModel());
            }
            rows.add(new UsageSummary.ModelRow(first.getProvider(), first.getModel(), group.size(),
                    sum(group, UsageRecord::getInputTokens),
                    sum(group, UsageRecord::getOutputTokens),
                    sum(group, UsageRecord::getTotalTokens),
                    sum(group, UsageRecord::getCacheReadTokens),
                    sum(group, UsageRecord::getCacheWriteTokens),
                    cost(group), priced));
        }
        rows.sort(Comparator.comparing(UsageSummary.ModelRow::totalTokens).reversed());

        List<UsageRecord> upstream = records.stream()
                .filter(record -> record.getSource() == UsageSource.UPSTREAM).toList();
        List<UsageRecord> cached = records.stream()
                .filter(record -> record.getSource() == UsageSource.CACHE).toList();

        UsageSummary.Totals totals = new UsageSummary.Totals(records.size(),
                sum(records, UsageRecord::getInputTokens),
                sum(records, UsageRecord::getOutputTokens),
                sum(records, UsageRecord::getTotalTokens),
                cost(records), cost(upstream), cost(cached),
                upstream.size(), cached.size());

        return new UsageSummary(properties.getCost().getCurrency(), rows, totals,
                new ArrayList<>(unpriced));
    }

    @Transactional
    public void deleteAll() {
        repository.deleteAll();
    }

    private long sum(List<UsageRecord> records,
                     java.util.function.ToIntFunction<UsageRecord> field) {
        return records.stream().mapToLong(field::applyAsInt).sum();
    }

    /** Null rather than zero when nothing in the group was priced, so gaps stay visible. */
    private BigDecimal cost(List<UsageRecord> records) {
        Optional<BigDecimal> total = records.stream()
                .map(UsageRecord::getEstimatedCost)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal::add);
        return total.orElse(null);
    }
}
