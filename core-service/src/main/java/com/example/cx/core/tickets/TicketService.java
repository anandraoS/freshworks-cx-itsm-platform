package com.example.cx.core.tickets;

import com.example.cx.core.audit.AuditService;
import com.example.cx.core.cache.CacheService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.common.ErrorCode;
import com.example.cx.core.common.PageParams;
import com.example.cx.core.common.PageResponse;
import com.example.cx.core.common.TxHooks;
import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.security.AuthUser;
import com.example.cx.core.security.CurrentUser;
import com.example.cx.core.sla.SlaService;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.TicketDtos.ActivityDto;
import com.example.cx.core.tickets.TicketDtos.AssignRequest;
import com.example.cx.core.tickets.TicketDtos.AttachmentRequest;
import com.example.cx.core.tickets.TicketDtos.CommentDto;
import com.example.cx.core.tickets.TicketDtos.CreateTicketRequest;
import com.example.cx.core.tickets.TicketDtos.TicketDto;
import com.example.cx.core.tickets.TicketDtos.TicketQuery;
import com.example.cx.core.tickets.TicketDtos.TransitionRequest;
import com.example.cx.core.tickets.TicketDtos.UpdateTicketRequest;
import com.example.cx.core.workflow.WorkflowEngine;
import com.example.cx.core.workflow.WorkflowService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket use-cases. Every method derives the tenant from {@link TenantContext} (bound from the verified JWT) and the actor from
 * the security context; ids supplied by the client are always resolved with an explicit tenant predicate, and a ticket of
 * another tenant (or, for CUSTOMER users, of another requester) is reported as 404 - never 403 - so existence does not leak.
 */
@Service
public class TicketService {
    static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "priority", "status", "number", "resolutionDueAt", "title");
    private static final int MAX_COMMENTS = 500, MAX_ACTIVITY = 200;
    private static final Set<String> CUSTOMER_ACTIVITY = Set.of(TicketActivity.CREATED, TicketActivity.STATUS_CHANGED);

    private final TicketRepository tickets;
    private final TicketCommentRepository comments;
    private final TicketAttachmentRepository attachments;
    private final TicketCategoryRepository categories;
    private final TicketActivityRepository activityRepo;
    private final UserRepository users;
    private final TicketMapper mapper;
    private final TicketActivityService activity;
    private final TicketEvents events;
    private final SlaService sla;
    private final WorkflowService workflows;
    private final WorkflowEngine engine;
    private final AuditService audit;
    private final CacheService cache;
    private final MeterRegistry meters;

    public TicketService(TicketRepository tickets, TicketCommentRepository comments, TicketAttachmentRepository attachments,
                         TicketCategoryRepository categories, TicketActivityRepository activityRepo,
                         UserRepository users, TicketMapper mapper, TicketActivityService activity, TicketEvents events,
                         SlaService sla, WorkflowService workflows, WorkflowEngine engine, AuditService audit, CacheService cache,
                         MeterRegistry meters) {
        this.tickets = tickets;
        this.comments = comments;
        this.attachments = attachments;
        this.categories = categories;
        this.activityRepo = activityRepo;
        this.users = users;
        this.mapper = mapper;
        this.activity = activity;
        this.events = events;
        this.sla = sla;
        this.workflows = workflows;
        this.engine = engine;
        this.audit = audit;
        this.cache = cache;
        this.meters = meters;
    }

    // ---- reads ------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<TicketDto> search(TicketQuery q, Integer page, Integer size, String sort) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        boolean sortByPriority = false, desc = true;
        Pageable pageable;
        if (sort != null && sort.split(",")[0].trim().equals("priority")) {
            // priority sorts by severity RANK (CASE expression in the specification), not alphabetically
            sortByPriority = true;
            desc = !(sort.contains(",") && "asc".equalsIgnoreCase(sort.split(",")[1].trim()));
            Pageable validated = PageParams.of(page, size, sort, SORT_FIELDS, Sort.unsorted());
            pageable = PageRequest.of(validated.getPageNumber(), validated.getPageSize());
        } else {
            pageable = PageParams.of(page, size, sort, SORT_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        }
        Page<Ticket> result = tickets.findAll(TicketSearch.build(tenant, me.userId(), !me.isStaff(), q, sortByPriority, desc), pageable);
        List<TicketDto> content = mapper.toDtos(result.getContent());
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public TicketDto get(UUID id) { return mapper.toDto(loadVisible(id)); }

    /** Tenant-scoped, requester-scoped for CUSTOMER users; 404 otherwise. */
    Ticket loadVisible(UUID id) {
        AuthUser me = CurrentUser.require();
        Ticket t = tickets.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (!me.isStaff() && !t.getRequesterId().equals(me.userId())) throw ApiException.notFound("Ticket");
        return t;
    }

    // ---- create -----------------------------------------------------------------------------------------------------

    /**
     * Creates a ticket in the CURRENT transaction (the idempotency wrapper owns the transaction boundary).
     * @param number the per-tenant ticket number, pre-allocated OUTSIDE this transaction by {@link TicketNumberAllocator} so no
     *               tenant-wide row lock is held while the ticket and its dependent rows are written
     */
    @Transactional
    public TicketDto create(CreateTicketRequest r, long number) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        AppUser actor = actor(me);
        TicketCategory category = null;
        if (r.categoryId() != null) {
            category = categories.findByIdAndTenantId(r.categoryId(), tenant)
                    .orElseThrow(() -> ApiException.validation("categoryId", "unknown category"));
        }
        Instant now = Instant.now();
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenant);
        t.setNumber(number);
        t.setType(r.type());
        t.setTitle(r.title().trim());
        t.setDescription(r.description().trim());
        t.setStatus(TicketStatus.NEW);
        t.setPriority(r.priority());
        t.setSeverity(r.severity());
        t.setCategoryId(r.categoryId());
        t.setTagList(r.tags());
        t.setRequesterId(me.userId());
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        sla.applyDueDates(t);
        sla.refreshStatus(t, now);

        // workflow rules (data-driven, validated, with safe fallback) may assign / re-prioritise / tag / escalate
        List<WorkflowEngine.Applied> applied = engine.apply(t, category == null ? null : category.getName(), workflows.active().definition());
        if (applied.stream().anyMatch(a -> a.action().equals("SET_PRIORITY"))) { sla.applyDueDates(t); sla.refreshStatus(t, now); }
        tickets.save(t);

        if (r.attachments() != null) {
            for (AttachmentRequest a : r.attachments()) {
                TicketAttachment att = new TicketAttachment();
                att.setId(UUID.randomUUID());
                att.setTenantId(tenant);
                att.setTicketId(t.getId());
                att.setFileName(a.fileName());
                att.setContentType(a.contentType());
                att.setSizeBytes(a.sizeBytes());
                att.setCreatedAt(now);
                attachments.save(att);
            }
        }
        activity.record(t.getId(), TicketActivity.CREATED, "Ticket created (" + t.getPriority() + ", " + t.getType() + ")",
                me.userId(), actor.getDisplayName());
        handleApplied(t, applied, me.userId(), actor.getDisplayName());
        events.created(t, me.userId(), actor.getDisplayName());
        audit.record("TICKET_CREATED", "TICKET", t.getId(), Map.of("number", t.getNumber(), "priority", t.getPriority().name()));
        invalidateAnalytics(tenant);
        meters.counter("cx.tickets.created").increment();
        return mapper.toDto(t);
    }

    // ---- update / assign / transition -------------------------------------------------------------------------------

    @Transactional
    public TicketDto update(UUID id, UpdateTicketRequest r) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        Ticket t = loadVisible(id);
        checkVersion(t, r.version());
        if (r.categoryId() != null && categories.findByIdAndTenantId(r.categoryId(), tenant).isEmpty())
            throw ApiException.validation("categoryId", "unknown category");

        List<String> changes = new ArrayList<>();
        if (!t.getTitle().equals(r.title().trim())) { t.setTitle(r.title().trim()); changes.add("title"); }
        if (!t.getDescription().equals(r.description().trim())) { t.setDescription(r.description().trim()); changes.add("description"); }
        if (t.getSeverity() != r.severity()) { changes.add("severity " + t.getSeverity() + " -> " + r.severity()); t.setSeverity(r.severity()); }
        if (!java.util.Objects.equals(t.getCategoryId(), r.categoryId())) { t.setCategoryId(r.categoryId()); changes.add("category"); }
        List<String> newTags = r.tags() == null ? List.of() : r.tags().stream().map(String::toLowerCase).distinct().toList();
        if (!t.tagList().equals(newTags)) { t.setTagList(newTags); changes.add("tags"); }
        boolean priorityChanged = t.getPriority() != r.priority();
        if (priorityChanged) { changes.add("priority " + t.getPriority() + " -> " + r.priority()); t.setPriority(r.priority()); }
        if (changes.isEmpty()) return mapper.toDto(t);

        Instant now = Instant.now();
        if (priorityChanged && !t.getStatus().isDone()) {
            sla.applyDueDates(t); // new priority = new SLA targets (measured from creation)
            if (t.getSlaPausedAt() != null) { /* stays PAUSED; due dates shift on resume */ }
            sla.refreshStatus(t, now);
        }
        t.setUpdatedAt(now);
        tickets.saveAndFlush(t);
        AppUser actor = actor(me);
        activity.record(t.getId(), TicketActivity.UPDATED, String.join("; ", changes), me.userId(), actor.getDisplayName());
        audit.record("TICKET_UPDATED", "TICKET", t.getId(), Map.of("changes", String.join("; ", changes)));
        invalidateAnalytics(tenant);
        return mapper.toDto(t);
    }

    @Transactional
    public TicketDto assign(UUID id, AssignRequest r) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        Ticket t = loadVisible(id);
        checkVersion(t, r.version());
        AppUser assignee = null;
        if (r.assigneeId() != null) {
            assignee = users.findByIdAndTenantId(r.assigneeId(), tenant)
                    .filter(u -> u.isActive() && u.getRoles().stream().anyMatch(com.example.cx.core.security.Role::isStaff))
                    .orElseThrow(() -> ApiException.validation("assigneeId", "must be an active staff user of this tenant"));
        }
        if (java.util.Objects.equals(t.getAssigneeId(), r.assigneeId())) return mapper.toDto(t);
        t.setAssigneeId(r.assigneeId());
        t.setUpdatedAt(Instant.now());
        tickets.saveAndFlush(t);
        AppUser actor = actor(me);
        activity.record(t.getId(), TicketActivity.ASSIGNED, assignee == null ? "Unassigned" : "Assigned to " + assignee.getDisplayName(),
                me.userId(), actor.getDisplayName());
        if (assignee != null) events.assigned(t, me.userId(), actor.getDisplayName(), assignee.getDisplayName());
        audit.record("TICKET_ASSIGNED", "TICKET", t.getId(), Map.of("assignee", assignee == null ? "none" : assignee.getId().toString()));
        invalidateAnalytics(tenant);
        return mapper.toDto(t);
    }

    @Transactional
    public TicketDto transition(UUID id, TransitionRequest r) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        Ticket t = loadVisible(id);
        checkVersion(t, r.version());
        TicketStatus from = t.getStatus(), to = r.toStatus();

        if (!me.isStaff()) { // customers may only reopen their own resolved / closed ticket
            if (to != TicketStatus.REOPENED || !from.isDone()) throw ApiException.forbidden("Customers may only reopen resolved or closed tickets");
        }
        if (!TicketStateMachine.canTransition(workflows.allowedTransitions(), from, to))
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Transition " + from + " -> " + to + " is not allowed");

        Instant now = Instant.now();
        boolean wasPausing = TicketStatus.SLA_PAUSING.contains(from), willPause = TicketStatus.SLA_PAUSING.contains(to);
        if (!wasPausing && willPause) sla.pause(t, now);
        else if (wasPausing && !willPause) sla.resume(t, now);

        t.setStatus(to);
        if (to == TicketStatus.RESOLVED) t.setResolvedAt(now);
        if (from.isDone() && !to.isDone()) t.setResolvedAt(null);
        if (to == TicketStatus.REOPENED) t.setReopenCount(t.getReopenCount() + 1);
        sla.refreshStatus(t, now);
        t.setUpdatedAt(now);

        AppUser actor = actor(me);
        String catName = t.getCategoryId() == null ? null : categories.findByIdAndTenantId(t.getCategoryId(), tenant).map(TicketCategory::getName).orElse(null);
        List<WorkflowEngine.Applied> applied = engine.apply(t, catName, workflows.active().definition());
        tickets.saveAndFlush(t);

        activity.record(t.getId(), TicketActivity.STATUS_CHANGED, from + " -> " + to + (r.reason() == null || r.reason().isBlank() ? "" : ": " + r.reason()),
                me.userId(), actor.getDisplayName());
        handleApplied(t, applied, me.userId(), actor.getDisplayName());
        events.statusChanged(t, from, to, me.userId(), actor.getDisplayName(), r.reason());
        audit.record("TICKET_TRANSITIONED", "TICKET", t.getId(), Map.of("from", from.name(), "to", to.name()));
        invalidateAnalytics(tenant);
        return mapper.toDto(t);
    }

    // ---- comments / activity ----------------------------------------------------------------------------------------

    /**
     * Adds a comment. Also the entry point other modules use (e.g. an approved AI reply: aiGenerated=true) so every comment
     * goes through the same authorisation, first-response, activity and event logic.
     */
    @Transactional
    public CommentDto addComment(UUID ticketId, String body, Visibility visibility, boolean aiGenerated) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        Ticket t = loadVisible(ticketId);
        if (!me.isStaff() && visibility == Visibility.INTERNAL) throw ApiException.forbidden("Customers cannot post internal comments");
        Instant now = Instant.now();
        AppUser actor = actor(me);

        TicketComment c = new TicketComment();
        c.setId(UUID.randomUUID());
        c.setTenantId(tenant);
        c.setTicketId(t.getId());
        c.setAuthorId(me.userId());
        c.setBody(body.trim());
        c.setVisibility(visibility);
        c.setAiGenerated(aiGenerated);
        c.setCreatedAt(now);
        comments.save(c);

        boolean staffReply = me.isStaff() && !t.getRequesterId().equals(me.userId()) && visibility == Visibility.PUBLIC;
        if (staffReply && t.getFirstRespondedAt() == null) {
            t.setFirstRespondedAt(now);
            sla.refreshStatus(t, now);
            t.setUpdatedAt(now);
            tickets.save(t);
        }
        activity.record(t.getId(), TicketActivity.COMMENTED, visibility + " comment" + (aiGenerated ? " (AI-assisted, human approved)" : ""),
                me.userId(), actor.getDisplayName());
        events.commented(t, c, me.userId(), actor.getDisplayName());
        invalidateAnalytics(tenant);
        return new CommentDto(c.getId(), c.getAuthorId(), actor.getDisplayName(), c.getBody(), c.getVisibility(), c.getCreatedAt(), aiGenerated);
    }

    @Transactional(readOnly = true)
    public List<CommentDto> listComments(UUID ticketId) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        Ticket t = loadVisible(ticketId);
        Pageable limit = PageRequest.of(0, MAX_COMMENTS);
        List<TicketComment> list = me.isStaff()
                ? comments.findByTenantIdAndTicketIdOrderByCreatedAtAscIdAsc(tenant, t.getId(), limit)
                : comments.findByTenantIdAndTicketIdAndVisibilityOrderByCreatedAtAscIdAsc(tenant, t.getId(), Visibility.PUBLIC, limit);
        Map<UUID, String> names = users.findByTenantIdAndIdIn(tenant, list.stream().map(TicketComment::getAuthorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        return list.stream().map(c -> new CommentDto(c.getId(), c.getAuthorId(), names.getOrDefault(c.getAuthorId(), "Unknown"),
                c.getBody(), c.getVisibility(), c.getCreatedAt(), c.isAiGenerated())).toList();
    }

    @Transactional(readOnly = true)
    public List<ActivityDto> activity(UUID ticketId) {
        AuthUser me = CurrentUser.require();
        UUID tenant = TenantContext.require();
        Ticket t = loadVisible(ticketId);
        Pageable limit = PageRequest.of(0, MAX_ACTIVITY);
        List<TicketActivity> list = me.isStaff()
                ? activityRepo.findByTenantIdAndTicketIdOrderByCreatedAtDescIdDesc(tenant, t.getId(), limit)
                : activityRepo.findByTenantIdAndTicketIdAndTypeInOrderByCreatedAtDescIdDesc(tenant, t.getId(), CUSTOMER_ACTIVITY, limit);
        List<ActivityDto> out = new ArrayList<>(list.stream().map(a -> new ActivityDto(a.getId(), a.getCreatedAt(), a.getActorId(),
                a.getActorName(), a.getType(), a.getDetails())).toList());
        Collections.reverse(out); // oldest first
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private void checkVersion(Ticket t, long expected) {
        if (t.getVersion() == null || t.getVersion() != expected)
            throw new ApiException(ErrorCode.TICKET_VERSION_CONFLICT, "Ticket was modified by someone else (current version "
                    + t.getVersion() + "); reload and retry");
    }

    private AppUser actor(AuthUser me) {
        return users.findByIdAndTenantId(me.userId(), TenantContext.require()).orElseThrow(() -> ApiException.notFound("User"));
    }

    private void invalidateAnalytics(UUID tenant) { TxHooks.afterCommit(() -> cache.evictByPrefix(tenant, "analytics:")); }

    /** Turns workflow-engine results into history rows and outbox events. */
    private void handleApplied(Ticket t, List<WorkflowEngine.Applied> applied, UUID actorId, String actorName) {
        for (WorkflowEngine.Applied a : applied) {
            activity.record(t.getId(), TicketActivity.WORKFLOW, a.detail(), actorId, "Workflow");
            switch (a.action()) {
                case "ASSIGN_TO_ROLE_LEAST_LOADED" -> {
                    Optional<AppUser> u = users.findByIdAndTenantId(a.assigneeId(), TenantContext.require());
                    activity.record(t.getId(), TicketActivity.ASSIGNED, "Assigned to " + u.map(AppUser::getDisplayName).orElse("agent") + " (workflow)", actorId, "Workflow");
                    events.assigned(t, actorId, "Workflow", u.map(AppUser::getDisplayName).orElse("an agent"));
                }
                case "ESCALATE" -> events.escalated(t, t.getEscalationLevel(), "Workflow rule");
                case "NOTIFY" -> events.workflowNotify(t, a.detail(), a.notifyRecipients());
                default -> { }
            }
        }
    }
}
