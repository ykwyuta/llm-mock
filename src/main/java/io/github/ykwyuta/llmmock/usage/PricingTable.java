package io.github.ykwyuta.llmmock.usage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.ykwyuta.llmmock.config.LlmMockProperties;

/**
 * Turns token counts into money using the configured price list.
 *
 * <p>The list ships empty. Vendor prices change, and a stale hard-coded number would
 * silently produce a confident, wrong total - worse than reporting no total and saying
 * which models are unpriced.
 */
@Component
public class PricingTable {

    private static final Logger log = LoggerFactory.getLogger(PricingTable.class);

    /** Vendors quote per million tokens, so that is the unit the price list uses. */
    private static final BigDecimal TOKENS_PER_UNIT = new BigDecimal("1000000");

    private final LlmMockProperties properties;

    public PricingTable(LlmMockProperties properties) {
        this.properties = properties;
    }

    /** The first matching entry wins, so order the list most specific first. */
    public Optional<LlmMockProperties.Price> priceFor(String model) {
        if (model == null) {
            return Optional.empty();
        }
        for (LlmMockProperties.Price price : properties.getCost().getPricing()) {
            String pattern = price.getModelPattern();
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            try {
                if (Pattern.compile(pattern).matcher(model).find()) {
                    return Optional.of(price);
                }
            } catch (PatternSyntaxException ex) {
                log.warn("Price entry has an invalid model pattern '{}': {}", pattern,
                        ex.getMessage());
            }
        }
        return Optional.empty();
    }

    public boolean isPriced(String model) {
        return priceFor(model).isPresent();
    }

    /** Null when the model has no price entry, so an unpriced call is never counted as free. */
    public BigDecimal estimate(String model, TokenUsage usage) {
        return priceFor(model).map(price -> {
            BigDecimal total = BigDecimal.ZERO;
            // Cached input is billed at its own rate, so it is not also billed as input.
            int billedInput = Math.max(0, usage.inputTokens() - usage.cacheReadTokens());
            total = total.add(component(price.getInput(), billedInput));
            total = total.add(component(price.getOutput(), usage.outputTokens()));
            total = total.add(component(price.getCacheRead(), usage.cacheReadTokens()));
            total = total.add(component(price.getCacheWrite(), usage.cacheWriteTokens()));
            return total.setScale(10, RoundingMode.HALF_UP);
        }).orElse(null);
    }

    private BigDecimal component(BigDecimal pricePerMillion, int tokens) {
        if (pricePerMillion == null || tokens <= 0) {
            return BigDecimal.ZERO;
        }
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens), MathContext.DECIMAL64)
                .divide(TOKENS_PER_UNIT, MathContext.DECIMAL64);
    }
}
