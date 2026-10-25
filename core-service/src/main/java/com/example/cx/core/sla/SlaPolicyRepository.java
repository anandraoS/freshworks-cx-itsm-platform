package com.example.cx.core.sla;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {
    /** At most 4 rows per tenant (one per priority; unique constraint), so this is naturally bounded. */
    List<SlaPolicy> findByTenantId(UUID tenantId);
    Optional<SlaPolicy> findByIdAndTenantId(UUID id, UUID tenantId);
}
