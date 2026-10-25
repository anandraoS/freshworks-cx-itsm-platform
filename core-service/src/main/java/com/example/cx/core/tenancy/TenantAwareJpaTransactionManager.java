package com.example.cx.core.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The single choke point where tenancy is bound to persistence, for every transaction (web, scheduler, listener):
 *  - enables the Hibernate "tenantFilter" with the current {@link TenantContext} tenant on the transaction's Session;
 *  - when {@code pgRls=true} (docker profile, PostgreSQL) also executes
 *    {@code select set_config('app.tenant_id', ?, true)} which is SET LOCAL semantics: the value lives only until the
 *    transaction ends, so a pooled connection can never leak one tenant's id into the next borrower.
 * When no tenant is bound (login lookup by slug, system jobs) neither is applied; RLS then sees NULL and fails closed.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {
    private final boolean pgRls;

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf, boolean pgRls) {
        super(emf);
        this.pgRls = pgRls;
    }

    @Override
    protected EntityManager createEntityManagerForTransaction() {
        EntityManager em = super.createEntityManagerForTransaction();
        TenantContext.current().ifPresent(t ->
                em.unwrap(Session.class).enableFilter(TenantOwned.FILTER).setParameter("tenantId", t));
        return em;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        if (!pgRls) return;
        UUID tenant = TenantContext.current().orElse(null);
        if (tenant == null) return;
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        holder.getEntityManager().unwrap(Session.class).doWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenant.toString());
                ps.execute();
            }
        });
    }
}
