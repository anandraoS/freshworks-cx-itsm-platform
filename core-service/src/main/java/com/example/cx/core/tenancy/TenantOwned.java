package com.example.cx.core.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Base of every tenant-owned entity. Isolation is layered (defence in depth):
 *  1. Hibernate filter "tenantFilter" (enabled per transaction by {@link TenantAwareJpaTransactionManager}) restricts every
 *     JPQL/Criteria query to the bound tenant. NOTE: filters do NOT apply to em.find()/getById(), so
 *  2. repositories use explicit tenant-scoped finders (findByIdAndTenantId...), and
 *  3. @PrePersist refuses to persist a row for a tenant different from the bound one, and
 *  4. PostgreSQL RLS (docker profile only) enforces the same predicate inside the database.
 */
@MappedSuperclass
@FilterDef(name = TenantOwned.FILTER, parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Getter
@Setter
public abstract class TenantOwned {
    public static final String FILTER = "tenantFilter";
    public static final String FILTER_CONDITION = "tenant_id = :tenantId";

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @PrePersist
    void assignOrVerifyTenant() {
        UUID bound = TenantContext.current().orElse(null);
        if (tenantId == null) {
            if (bound == null) throw new IllegalStateException("Cannot persist tenant-owned entity without a tenant");
            tenantId = bound;
        } else if (bound != null && !bound.equals(tenantId)) {
            throw new IllegalStateException("Cross-tenant write blocked");
        }
    }
}
