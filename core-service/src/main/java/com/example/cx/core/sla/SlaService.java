package com.example.cx.core.sla;

import com.example.cx.core.audit.AuditService;
import com.example.cx.core.cache.CacheService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.common.TxHooks;
import com.example.cx.core.config.CxProperties;
import com.example.cx.core.identity.TenantSettingsService;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.SlaStatus;
import com.example.cx.core.tickets.Ticket;
import com.example.cx.core.tickets.TicketActivity;
import com.example.cx.core.tickets.TicketActivityService;
import com.example.cx.core.tickets.TicketEvents;
import com.example.cx.core.tickets.TicketRepository;
import com.example.cx.core.tickets.TicketStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SLA policies, due-date computation (business hours), pause/resume and the per-tenant escalation / breach scan. */
@Service
public class SlaService {
    public static final String POLICY_CACHE_KEY = "sla:policies";

    public record SlaPolicyDto(UUID id, Priority priority, int firstResponseMinutes, int resolutionMinutes,
                               List<Integer> escalationAfterPercent) {}

    public record SlaPolicyRequest(@NotNull Priority priority,
                                   @Min(1) @Max(200_000) int firstResponseMinutes,
                                   @Min(1) @Max(200_000) int resolutionMinutes,
                                   @NotEmpty @Size(max = 5) List<@Min(1) @Max(200) Integer> escalationAfterPercent) {}

    private final SlaPolicyRepository policies;
    private final TenantSettingsService settings;
    private final CacheService cache;
    private final AuditService audit;
    private final TicketRepository tickets;
    private final TicketActivityService activity;
    private final TicketEvents events;
    private final CxProperties props;

    public SlaService(SlaPolicyRepository policies, TenantSettingsService settings, CacheService cache, AuditService audit,
                      TicketRepository tickets, TicketActivityService activity, TicketEvents events, CxProperties props) {
        this.policies = policies;
        this.settings = settings;
        this.cache = cache;
        this.audit = audit;
        this.tickets = tickets;
        this.activity = activity;
        this.events = events;
        this.props = props;
    }

    // ---- policies ---------------------------------------------------------------------------------------------------

    /** Cache-aside (tenant-keyed, 5 min TTL, evicted on update). */
    public List<SlaPolicyDto> list() {
        UUID t = TenantContext.require();
        return cache.getOrLoad(t, POLICY_CACHE_KEY, Duration.ofMinutes(5), new TypeReference<List<SlaPolicyDto>>() {},
                () -> policies.findByTenantId(t).stream().map(SlaService::toDto)
                        .sorted(Comparator.comparing(p -> p.priority().ordinal())).toList());
    }

    public Optional<SlaPolicyDto> policyFor(Priority p) {
        return list().stream().filter(x -> x.priority() == p).findFirst();
    }

    @Transactional
    public SlaPolicyDto update(UUID id, SlaPolicyRequest r) {
        UUID t = TenantContext.require();
        SlaPolicy p = policies.findByIdAndTenantId(id, t).orElseThrow(() -> ApiException.notFound("SLA policy"));
        if (p.getPriority() != r.priority()) throw ApiException.validation("priority", "priority of an existing policy cannot be changed");
        if (r.resolutionMinutes() < r.firstResponseMinutes())
            throw ApiException.validation("resolutionMinutes", "must be >= firstResponseMinutes");
        List<Integer> pct = r.escalationAfterPercent();
        if (pct.size() != new HashSet<>(pct).size() || !pct.equals(pct.stream().sorted().toList()))
            throw ApiException.validation("escalationAfterPercent", "must be unique and ascending");
        p.setFirstResponseMinutes(r.firstResponseMinutes());
        p.setResolutionMinutes(r.resolutionMinutes());
        p.setEscalationAfterPercent(pct);
        p.setUpdatedAt(Instant.now());
        policies.save(p);
        audit.record("SLA_POLICY_UPDATED", "SLA_POLICY", p.getId(), Map.of("priority", p.getPriority().name(),
                "firstResponseMinutes", p.getFirstResponseMinutes(), "resolutionMinutes", p.getResolutionMinutes(),
                "escalationAfterPercent", pct.toString()));
        TxHooks.afterCommit(() -> cache.evict(t, POLICY_CACHE_KEY));
        return toDto(p);
    }

    static SlaPolicyDto toDto(SlaPolicy p) {
        return new SlaPolicyDto(p.getId(), p.getPriority(), p.getFirstResponseMinutes(), p.getResolutionMinutes(), p.getEscalationAfterPercent());
    }

    // ---- ticket SLA state -------------------------------------------------------------------------------------------

    public BusinessCalendar calendar() { return BusinessCalendar.of(settings.calendar()); }

    /** (Re)computes the due dates from createdAt using the CURRENT policy of the ticket priority (business-hours aware). */
    public void applyDueDates(Ticket t) {
        Optional<SlaPolicyDto> p = policyFor(t.getPriority());
        if (p.isEmpty()) {
            t.setFirstResponseDueAt(null);
            t.setResolutionDueAt(null);
            return;
        }
        BusinessCalendar cal = calendar();
        t.setFirstResponseDueAt(cal.addBusinessMinutes(t.getCreatedAt(), p.get().firstResponseMinutes()));
        t.setResolutionDueAt(cal.addBusinessMinutes(t.getCreatedAt(), p.get().resolutionMinutes()));
    }

    public void refreshStatus(Ticket t, Instant now) {
        t.setSlaStatus(evaluate(t, now, props.sla().atRiskPercent(), calendar()).status());
    }

    /** Evaluates a ticket with business-minute percentages (tenant calendar, current policy targets). */
    public SlaCalculator.Result evaluate(Ticket t, Instant now, int atRiskPercent, BusinessCalendar cal) {
        Optional<SlaPolicyDto> p = policyFor(t.getPriority());
        SlaCalculator.Targets targets = p.map(x -> new SlaCalculator.Targets(x.firstResponseMinutes(), x.resolutionMinutes()))
                .orElse(new SlaCalculator.Targets(0, 0));
        return SlaCalculator.evaluate(t, p.map(SlaPolicyDto::escalationAfterPercent).orElse(List.of()), now, atRiskPercent, cal, targets);
    }

    /** Pause on PENDING / ON_HOLD: the clock stops; due dates are shifted when work resumes. */
    public void pause(Ticket t, Instant now) {
        if (t.getSlaPausedAt() == null) t.setSlaPausedAt(now);
        t.setSlaStatus(SlaStatus.PAUSED);
    }

    /** Resume: push both due dates out by the BUSINESS minutes spent paused. */
    public void resume(Ticket t, Instant now) {
        Instant pausedAt = t.getSlaPausedAt();
        if (pausedAt == null) return;
        BusinessCalendar cal = calendar();
        long paused = cal.businessMinutesBetween(pausedAt, now);
        if (paused > 0) {
            if (t.getFirstResponseDueAt() != null && t.getFirstRespondedAt() == null)
                t.setFirstResponseDueAt(cal.addBusinessMinutes(t.getFirstResponseDueAt(), paused));
            if (t.getResolutionDueAt() != null)
                t.setResolutionDueAt(cal.addBusinessMinutes(t.getResolutionDueAt(), paused));
        }
        t.setSlaPausedAt(null);
    }

    public List<Integer> thresholds(Priority p) {
        return policyFor(p).map(SlaPolicyDto::escalationAfterPercent).orElse(List.of());
    }

    public boolean isPausing(TicketStatus s) { return TicketStatus.SLA_PAUSING.contains(s); }

    // ---- scheduled scan (called per tenant by SlaMonitorJob) --------------------------------------------------------

    /**
     * Examines at most {@code batchSize} open tickets of the CURRENT tenant (least recently checked first). Idempotent: the
     * escalation level only ever increases and {@code breach_notified} flips once, so re-running (or two nodes overlapping) never
     * publishes the same escalation/breach twice for a ticket. Uses bulk UPDATEs so user-facing optimistic versions are untouched.
     *
     * @return number of tickets whose SLA state changed
     */
    @Transactional
    public int scanCurrentTenant(int batchSize) {
        UUID tenant = TenantContext.require();
        Instant now = Instant.now();
        int changed = 0;
        BusinessCalendar cal = calendar();
        for (Ticket t : tickets.slaScanBatch(tenant, TicketStatus.DONE, Instant.EPOCH, PageRequest.of(0, batchSize))) {
            SlaCalculator.Result r = evaluate(t, now, props.sla().atRiskPercent(), cal);
            boolean newBreach = r.status() == SlaStatus.BREACHED && !t.isBreachNotified();
            boolean newEscalation = r.escalationLevel() > t.getEscalationLevel();
            boolean stateChanged = r.status() != t.getSlaStatus() || newBreach || newEscalation;
            tickets.updateSlaState(t.getId(), tenant, r.status(), r.escalationLevel(), t.isBreachNotified() || newBreach, now);
            if (newEscalation) {
                activity.record(t.getId(), TicketActivity.ESCALATED, "SLA escalation to level " + r.escalationLevel(), null, "System");
                events.escalated(t, r.escalationLevel(), "SLA threshold reached");
            }
            if (newBreach) {
                activity.record(t.getId(), TicketActivity.SLA_BREACHED, "SLA breached (" + r.breachType() + ")", null, "System");
                events.slaBreached(t, r.breachType());
            }
            if (stateChanged) changed++;
        }
        return changed;
    }
}
