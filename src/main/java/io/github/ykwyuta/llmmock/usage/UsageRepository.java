package io.github.ykwyuta.llmmock.usage;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.ykwyuta.llmmock.core.Provider;

public interface UsageRepository extends JpaRepository<UsageRecord, Long> {

    @Query("""
            select u from UsageRecord u
            where (:provider is null or u.provider = :provider)
              and (:model is null or u.model = :model)
              and (:source is null or u.source = :source)
            order by u.id desc
            """)
    List<UsageRecord> search(@Param("provider") Provider provider,
                             @Param("model") String model,
                             @Param("source") UsageSource source,
                             Pageable pageable);

    /**
     * Per-model aggregate, computed in the database rather than by loading every row.
     *
     * <p>Columns: provider, model, requests, input, output, total, cacheRead, cacheWrite,
     * cost. {@code sum} over the cost yields null when nothing in the group was priced,
     * which is exactly the distinction the report needs to keep.
     */
    @Query("""
            select u.provider, u.model, count(u),
                   sum(u.inputTokens), sum(u.outputTokens), sum(u.totalTokens),
                   sum(u.cacheReadTokens), sum(u.cacheWriteTokens), sum(u.estimatedCost)
            from UsageRecord u
            where (:provider is null or u.provider = :provider)
              and (:source is null or u.source = :source)
            group by u.provider, u.model
            """)
    List<Object[]> aggregateByModel(@Param("provider") Provider provider,
                                    @Param("source") UsageSource source);

    /** The same shape keyed by source, which is what separates spend from savings. */
    @Query("""
            select u.source, count(u),
                   sum(u.inputTokens), sum(u.outputTokens), sum(u.totalTokens),
                   sum(u.estimatedCost)
            from UsageRecord u
            where (:provider is null or u.provider = :provider)
              and (:source is null or u.source = :source)
            group by u.source
            """)
    List<Object[]> aggregateBySource(@Param("provider") Provider provider,
                                     @Param("source") UsageSource source);

    @Query("select min(u.id) from UsageRecord u")
    Long lowestId();

    @Query("select max(u.id) from UsageRecord u")
    Long highestId();

    @Modifying
    @Query("delete from UsageRecord u where u.id <= :id")
    int deleteUpToId(@Param("id") long id);
}
