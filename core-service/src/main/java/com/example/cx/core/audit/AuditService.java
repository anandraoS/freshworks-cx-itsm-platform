package com.example.cx.core.audit;

import com.example.cx.core.common.CorrelationIdFilter;
import com.example.cx.core.common.PageParams;
import com.example.cx.core.common.Redaction;
import com.example.cx.core.security.AuthUser;
import com.example.cx.core.security.CurrentUser;
import com.example.cx.core.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * EXTENSION POINT: every module records security/business-relevant actions here. Joins the caller's transaction so the
 * audit row commits atomically with the change it describes. Details are redacted and truncated.
 */
@Service
public class AuditService {
    private final AuditEventRepository repo;
    private final ObjectMapper mapper;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public AuditService(AuditEventRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entityType, Object entityId, Map<String, ?> details) {
        AuthUser u = CurrentUser.optional().orElse(null);
        write(TenantContext.require(), u == null ? null : u.userId(), u == null ? null : u.email(),
                action, entityType, entityId, details);
    }

    /** For flows without a security context (login, scheduled jobs). Tenant must be bound or passed. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordAs(UUID tenantId, UUID actorId, String actorEmail, String action, String entityType,
                         Object entityId, Map<String, ?> details) {
        write(tenantId, actorId, actorEmail, action, entityType, entityId, details);
    }

    private void write(UUID tenantId, UUID actorId, String actorEmail, String action, String entityType,
                       Object entityId, Map<String, ?> details) {
        AuditEvent e = new AuditEvent();
        e.setId(UUID.randomUUID());
        e.setTenantId(tenantId);
        e.setOccurredAt(Instant.now());
        e.setActorId(actorId);
        e.setActorEmail(actorEmail);
        e.setAction(action);
        e.setEntityType(entityType);
        e.setEntityId(entityId == null ? null : String.valueOf(entityId));
        e.setCorrelationId(MDC.get(CorrelationIdFilter.MDC_KEY));
        e.setDetails(serialize(details));
        repo.save(e);
    }

    private String serialize(Map<String, ?> details) {
        if (details == null || details.isEmpty()) return null;
        try {
            String s = Redaction.redact(mapper.writeValueAsString(details));
            return s.length() > 3900 ? s.substring(0, 3900) + "...(truncated)" : s;
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"unserializable details\"}";
        }
    }

    /** Read-only criteria query (newest first). The repository deliberately exposes no search/delete API. */
    @Transactional(readOnly = true)
    public Page<AuditEvent> search(String entityType, UUID actorId, Integer page, Integer size) {
        UUID tenant = TenantContext.require();
        Pageable p = PageParams.of(page, size, null, Set.of(), Sort.by(Sort.Direction.DESC, "occurredAt", "id"));
        var cb = em.getCriteriaBuilder();

        var cq = cb.createQuery(AuditEvent.class);
        var root = cq.from(AuditEvent.class);
        cq.select(root).where(predicates(cb, root, tenant, entityType, actorId)).orderBy(cb.desc(root.get("occurredAt")), cb.desc(root.get("id")));
        List<AuditEvent> rows = em.createQuery(cq).setFirstResult((int) p.getOffset()).setMaxResults(p.getPageSize()).getResultList();

        var cc = cb.createQuery(Long.class);
        var croot = cc.from(AuditEvent.class);
        cc.select(cb.count(croot)).where(predicates(cb, croot, tenant, entityType, actorId));
        long total = em.createQuery(cc).getSingleResult();
        return new org.springframework.data.domain.PageImpl<>(rows, p, total);
    }

    private static Predicate[] predicates(jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.Root<AuditEvent> root,
                                          UUID tenant, String entityType, UUID actorId) {
        List<Predicate> ps = new ArrayList<>();
        ps.add(cb.equal(root.get("tenantId"), tenant));
        if (entityType != null && !entityType.isBlank()) ps.add(cb.equal(root.get("entityType"), entityType));
        if (actorId != null) ps.add(cb.equal(root.get("actorId"), actorId));
        return ps.toArray(Predicate[]::new);
    }
}
