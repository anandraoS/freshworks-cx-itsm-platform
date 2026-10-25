package com.example.cx.core.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {
    Optional<Workflow> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Workflow> findFirstByTenantIdAndActiveTrue(UUID tenantId);

    List<Workflow> findByTenantIdOrderByVersionDesc(UUID tenantId, Pageable limit);

    @Query("select coalesce(max(w.version), 0) from Workflow w where w.tenantId = :t")
    int maxVersion(@Param("t") UUID tenantId);

    @Modifying
    @Query("update Workflow w set w.active = false where w.tenantId = :t and w.active = true")
    int deactivateAll(@Param("t") UUID tenantId);
}
