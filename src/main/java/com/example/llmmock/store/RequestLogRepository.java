package com.example.llmmock.store;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

    @Query("""
            select l from RequestLog l
            where (:provider is null or l.provider = :provider)
              and (:model is null or l.model = :model)
              and (:endpoint is null or l.endpoint = :endpoint)
            order by l.id desc
            """)
    List<RequestLog> search(@Param("provider") com.example.llmmock.core.Provider provider,
                           @Param("model") String model,
                           @Param("endpoint") String endpoint,
                           Pageable pageable);
}
