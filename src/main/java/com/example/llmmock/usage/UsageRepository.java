package com.example.llmmock.usage;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.llmmock.core.Provider;

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
}
