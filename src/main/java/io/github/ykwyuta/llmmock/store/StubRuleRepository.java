package io.github.ykwyuta.llmmock.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StubRuleRepository extends JpaRepository<StubRule, Long> {

    Optional<StubRule> findByName(String name);

    List<StubRule> findByEnabledTrueOrderByPriorityDescIdAsc();
}
