package com.example.cx.core.workflow;

import com.example.cx.core.audit.AuditService;
import com.example.cx.core.cache.CacheService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.common.FieldErrorDto;
import com.example.cx.core.common.TxHooks;
import com.example.cx.core.security.CurrentUser;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.workflow.WorkflowModel.Definition;
import com.example.cx.core.workflow.WorkflowModel.Rule;
import com.example.cx.core.workflow.WorkflowModel.Transition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned workflows. Creating never mutates an existing version (new row, version = max + 1); activation flips the single
 * active flag. SAFE FALLBACK: if a tenant has no active workflow, or the stored definition cannot be parsed / no longer passes
 * validation, the built-in default (no rules, contract transitions) is used and a counter + warning is emitted, so a bad
 * configuration can never block ticket handling.
 */
@Service
public class WorkflowService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    public static final String CACHE_KEY = "workflow:active";

    public record WorkflowRequest(@NotBlank @Size(max = 120) String name, List<Rule> rules, List<Transition> transitions) {}

    public record WorkflowDto(UUID id, String name, int version, boolean active, Instant createdAt, List<Rule> rules,
                              List<Transition> transitions) {}

    public record ValidationResult(boolean valid, List<String> errors) {}

    /** Cached value (Jackson-serialisable). */
    public record ActiveWorkflow(UUID id, int version, Definition definition) {}

    private final WorkflowRepository repo;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final CacheService cache;
    private final MeterRegistry meters;

    public WorkflowService(WorkflowRepository repo, ObjectMapper mapper, AuditService audit, CacheService cache, MeterRegistry meters) {
        this.repo = repo;
        this.mapper = mapper;
        this.audit = audit;
        this.cache = cache;
        this.meters = meters;
    }

    @Transactional(readOnly = true)
    public List<WorkflowDto> list() {
        return repo.findByTenantIdOrderByVersionDesc(TenantContext.require(), PageRequest.of(0, 200)).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public WorkflowDto get(UUID id) {
        return toDto(repo.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Workflow")));
    }

    public ValidationResult validate(WorkflowRequest r) {
        List<String> errors = WorkflowValidator.validate(r.rules(), r.transitions());
        return new ValidationResult(errors.isEmpty(), errors);
    }

    @Transactional
    public WorkflowDto create(WorkflowRequest r) {
        UUID t = TenantContext.require();
        List<String> errors = WorkflowValidator.validate(r.rules(), r.transitions());
        if (!errors.isEmpty()) throw ApiException.validation(errors.stream().map(e -> new FieldErrorDto("workflow", e)).toList());
        Definition def = new Definition(r.rules(), r.transitions());
        Workflow w = new Workflow();
        w.setId(UUID.randomUUID());
        w.setTenantId(t);
        w.setName(r.name().trim());
        w.setVersion(repo.maxVersion(t) + 1);
        w.setActive(false);
        w.setDefinition(toJson(def));
        w.setCreatedBy(CurrentUser.optional().map(u -> u.userId()).orElse(null));
        w.setCreatedAt(Instant.now());
        repo.save(w);
        audit.record("WORKFLOW_CREATED", "WORKFLOW", w.getId(), Map.of("name", w.getName(), "version", w.getVersion(),
                "rules", def.rules().size(), "transitions", def.transitions().size()));
        return toDto(w);
    }

    /** Used by tenant provisioning (system context): creates AND activates a version. */
    @Transactional
    public WorkflowDto createActive(WorkflowRequest r) {
        WorkflowDto dto = create(r);
        return activate(dto.id());
    }

    @Transactional
    public WorkflowDto activate(UUID id) {
        UUID t = TenantContext.require();
        Workflow w = repo.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("Workflow"));
        List<String> errors = validateStored(w);
        if (!errors.isEmpty()) throw ApiException.validation(errors.stream().map(e -> new FieldErrorDto("workflow", e)).toList());
        repo.deactivateAll(t);
        w.setActive(true);
        repo.save(w);
        audit.record("WORKFLOW_ACTIVATED", "WORKFLOW", w.getId(), Map.of("name", w.getName(), "version", w.getVersion()));
        TxHooks.afterCommit(() -> cache.evict(t, CACHE_KEY));
        return toDto(w);
    }

    /** The definition currently in force for the tenant (cached; falls back to the built-in default on any problem). */
    public ActiveWorkflow active() {
        UUID t = TenantContext.require();
        return cache.getOrLoad(t, CACHE_KEY, Duration.ofMinutes(5), new TypeReference<ActiveWorkflow>() {}, () -> loadActive(t));
    }

    public Map<com.example.cx.core.tickets.TicketStatus, Set<com.example.cx.core.tickets.TicketStatus>> allowedTransitions() {
        return active().definition().transitionTable();
    }

    ActiveWorkflow loadActive(UUID tenantId) {
        Optional<Workflow> w = repo.findFirstByTenantIdAndActiveTrue(tenantId);
        if (w.isEmpty()) {
            meters.counter("cx.workflow.fallback", "reason", "none_active").increment();
            return new ActiveWorkflow(null, 0, WorkflowModel.builtInDefault());
        }
        try {
            List<String> errors = validateStored(w.get());
            if (!errors.isEmpty()) throw new IllegalStateException(errors.get(0));
            return new ActiveWorkflow(w.get().getId(), w.get().getVersion(), mapper.readValue(w.get().getDefinition(), Definition.class));
        } catch (Exception e) {
            meters.counter("cx.workflow.fallback", "reason", "invalid_definition").increment();
            log.warn("Active workflow {} of tenant {} is unusable ({}); falling back to the built-in default",
                    w.get().getId(), tenantId, e.getClass().getSimpleName());
            return new ActiveWorkflow(null, 0, WorkflowModel.builtInDefault());
        }
    }

    private List<String> validateStored(Workflow w) {
        try {
            Definition d = mapper.readValue(w.getDefinition(), Definition.class);
            return WorkflowValidator.validate(d.rules(), d.transitions());
        } catch (JsonProcessingException e) {
            return List.of("stored definition is not readable");
        }
    }

    private WorkflowDto toDto(Workflow w) {
        Definition d;
        try {
            d = mapper.readValue(w.getDefinition(), Definition.class);
        } catch (JsonProcessingException e) {
            d = WorkflowModel.builtInDefault();
        }
        return new WorkflowDto(w.getId(), w.getName(), w.getVersion(), w.isActive(), w.getCreatedAt(), d.rules(), d.transitions());
    }

    private String toJson(Definition d) {
        try {
            return mapper.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
