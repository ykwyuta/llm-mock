package io.github.ykwyuta.llmmock.store;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

    @Query("""
            select l from RequestLog l
            where (:provider is null or l.provider = :provider)
              and (:model is null or l.model = :model)
              and (:endpoint is null or l.endpoint = :endpoint)
            order by l.id desc
            """)
    List<RequestLog> search(@Param("provider") io.github.ykwyuta.llmmock.core.Provider provider,
                           @Param("model") String model,
                           @Param("endpoint") String endpoint,
                           Pageable pageable);

    @Query("select min(l.id) from RequestLog l")
    Long lowestId();

    @Query("select max(l.id) from RequestLog l")
    Long highestId();

    @Modifying
    @Query("delete from RequestLog l where l.id <= :id")
    int deleteUpToId(@Param("id") long id);
}
