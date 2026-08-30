package io.github.ykwyuta.llmmock.store;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decrements a limited-use stub's remaining count in its own transaction.
 *
 * <p>A stub that simulates a failure aborts the caller's transaction by design. Consuming
 * the use inside that transaction would roll back with it, so a "fail once, then succeed"
 * rule would fail forever instead of exactly once.
 */
@Component
public class StubUsage {

    private final StubRuleRepository stubs;

    public StubUsage(StubRuleRepository stubs) {
        this.stubs = stubs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(Long stubId) {
        stubs.findById(stubId).ifPresent(rule -> {
            if (rule.getRemainingUses() != null) {
                rule.setRemainingUses(rule.getRemainingUses() - 1);
                stubs.save(rule);
            }
        });
    }
}
