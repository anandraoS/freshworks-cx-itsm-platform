package com.example.cx.core.audit;

import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Deliberately NOT a CrudRepository / JpaSpecificationExecutor (the latter exposes delete(Specification)): only save() and a count
 * are exposed here. Searching is done by AuditService with an explicit read-only criteria query. No update / delete operation exists.
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {
    AuditEvent save(AuditEvent e);
    long countByTenantId(UUID tenantId);
}
