package com.example.cx.core.sla;

import com.example.cx.core.config.CxProperties;
import com.example.cx.core.identity.Organization;
import com.example.cx.core.identity.OrganizationRepository;
import com.example.cx.core.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tenant-iterating scheduled job. It is a SYSTEM job: it binds the TenantContext itself, one tenant at a time (never from any
 * request), so every query and the PostgreSQL RLS variable are tenant-scoped. Work per tenant is bounded
 * ({@code cx.sla.batch-per-tenant}); a failure in one tenant is logged and does not stop the others.
 * Multi-node note: overlapping runs are harmless (idempotent), but a leader lock (e.g. ShedLock) would avoid redundant work.
 */
@Component
public class SlaMonitorJob {
    private static final Logger log = LoggerFactory.getLogger(SlaMonitorJob.class);
    private static final int TENANT_PAGE = 100;

    private final OrganizationRepository orgs;
    private final SlaService sla;
    private final CxProperties props;
    private final MeterRegistry meters;

    public SlaMonitorJob(OrganizationRepository orgs, SlaService sla, CxProperties props, MeterRegistry meters) {
        this.orgs = orgs;
        this.sla = sla;
        this.props = props;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${cx.sla.job-interval-ms:60000}", initialDelayString = "${cx.sla.job-interval-ms:60000}")
    public void scheduled() {
        if (props.sla().jobEnabled()) runOnce();
    }

    /** @return number of tickets whose SLA state changed across all tenants */
    public int runOnce() {
        int total = 0;
        int page = 0;
        Page<Organization> p;
        do {
            p = orgs.findAllByOrderBySlugAsc(PageRequest.of(page++, TENANT_PAGE));
            for (Organization o : p.getContent()) total += scanTenant(o.getId());
        } while (p.hasNext());
        meters.counter("cx.sla.job.runs").increment();
        return total;
    }

    private int scanTenant(UUID tenantId) {
        try {
            return TenantContext.callAs(tenantId, () -> sla.scanCurrentTenant(props.sla().batchPerTenant()));
        } catch (RuntimeException e) {
            meters.counter("cx.sla.job.errors").increment();
            log.warn("SLA scan failed for tenant {}: {}", tenantId, e.toString());
            return 0;
        }
    }
}
