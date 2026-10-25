package com.example.cx.core.docstore;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentEntityRepository extends JpaRepository<DocumentEntity, DocumentEntity.Pk> {
    @Query("select d from DocumentEntity d where d.tenantId = :t and d.collection = :c and d.docKey like :prefix escape '\\' order by d.docKey asc")
    List<DocumentEntity> byPrefix(@Param("t") UUID tenant, @Param("c") String collection, @Param("prefix") String likePrefix, Pageable limit);
}
