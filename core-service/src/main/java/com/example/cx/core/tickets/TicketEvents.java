package com.example.cx.core.tickets;

import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.Roles;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.outbox.OutboxService;
import com.example.cx.core.security.Role;
import com.example.cx.core.tenancy.TenantContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Builds the event payloads (flat string map, see platform-events DomainEvent) and enqueues them in the outbox. Must be called
 * inside the business transaction. Payload keys follow what the notification-service templates read
 * (ticketNumber, title, priority, assigneeName, fromStatus, toStatus, actorName, commentSnippet, escalationLevel, slaType,
 * recipientUserIds).
 */
@Component
public class TicketEvents {
    public static final String CREATED = "ticket.created", ASSIGNED = "ticket.assigned", STATUS_CHANGED = "ticket.status.changed",
            COMMENTED = "ticket.commented", ESCALATED = "ticket.escalated", SLA_BREACHED = "ticket.sla.breached",
            AI_DECIDED = "ai.suggestion.decided", WORKFLOW_NOTIFY = "ticket.workflow.notified";

    private final OutboxService outbox;
    private final UserRepository users;

    public TicketEvents(OutboxService outbox, UserRepository users) {
        this.outbox = outbox;
        this.users = users;
    }

    /** EXTENSION POINT for other modules (e.g. AI): build the standard ticket payload then add your own keys. */
    public Map<String, String> base(Ticket t, UUID actorId, String actorName, Collection<UUID> recipients, UUID excludeActor) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ticketId", t.getId().toString());
        m.put("ticketNumber", String.valueOf(t.getNumber()));
        m.put("title", t.getTitle());
        m.put("priority", t.getPriority().name());
        m.put("status", t.getStatus().name());
        if (actorId != null) m.put("actorId", actorId.toString());
        if (actorName != null) m.put("actorName", actorName);
        Set<UUID> r = new LinkedHashSet<>(recipients);
        r.remove(excludeActor);
        m.put("recipientUserIds", r.stream().map(UUID::toString).collect(Collectors.joining(",")));
        return m;
    }

    public void publish(String type, Map<String, String> payload) { outbox.publish(type, payload); }

    public void created(Ticket t, UUID actorId, String actorName) {
        Set<UUID> to = new LinkedHashSet<>(staff(Role.AGENT));
        if (t.getAssigneeId() != null) to.add(t.getAssigneeId());
        outbox.publish(CREATED, base(t, actorId, actorName, to, actorId));
    }

    public void assigned(Ticket t, UUID actorId, String actorName, String assigneeName) {
        if (t.getAssigneeId() == null) return;
        Map<String, String> m = base(t, actorId, actorName, Set.of(t.getAssigneeId()), actorId);
        m.put("assigneeId", t.getAssigneeId().toString());
        m.put("assigneeName", assigneeName);
        outbox.publish(ASSIGNED, m);
    }

    public void statusChanged(Ticket t, TicketStatus from, TicketStatus to, UUID actorId, String actorName, String reason) {
        Set<UUID> r = new LinkedHashSet<>();
        r.add(t.getRequesterId());
        if (t.getAssigneeId() != null) r.add(t.getAssigneeId());
        Map<String, String> m = base(t, actorId, actorName, r, actorId);
        m.put("fromStatus", from.name());
        m.put("toStatus", to.name());
        if (reason != null && !reason.isBlank()) m.put("reason", reason.length() > 200 ? reason.substring(0, 200) : reason);
        outbox.publish(STATUS_CHANGED, m);
    }

    /** INTERNAL comments are never announced to the requester. */
    public void commented(Ticket t, TicketComment c, UUID actorId, String actorName) {
        Set<UUID> r = new LinkedHashSet<>();
        if (c.getVisibility() == Visibility.PUBLIC) r.add(t.getRequesterId());
        if (t.getAssigneeId() != null) r.add(t.getAssigneeId());
        Map<String, String> m = base(t, actorId, actorName, r, actorId);
        m.put("visibility", c.getVisibility().name());
        String body = c.getBody();
        m.put("commentSnippet", body.length() > 120 ? body.substring(0, 120) + "..." : body);
        outbox.publish(COMMENTED, m);
    }

    public void escalated(Ticket t, int level, String reason) {
        Set<UUID> r = new LinkedHashSet<>(staff(Role.MANAGER));
        if (t.getAssigneeId() != null) r.add(t.getAssigneeId());
        Map<String, String> m = base(t, null, "System", r, null);
        m.put("escalationLevel", String.valueOf(level));
        if (reason != null) m.put("reason", reason);
        outbox.publish(ESCALATED, m);
    }

    public void slaBreached(Ticket t, String slaType) {
        Set<UUID> r = new LinkedHashSet<>(staff(Role.MANAGER));
        if (t.getAssigneeId() != null) r.add(t.getAssigneeId());
        Map<String, String> m = base(t, null, "System", r, null);
        m.put("slaType", slaType);
        outbox.publish(SLA_BREACHED, m);
    }

    public void workflowNotify(Ticket t, String message, Collection<UUID> recipients) {
        if (recipients.isEmpty()) return;
        Map<String, String> m = base(t, null, "Workflow", recipients, null);
        m.put("message", message);
        outbox.publish(WORKFLOW_NOTIFY, m);
    }

    private Set<UUID> staff(Role role) {
        UUID tenant = TenantContext.require();
        Set<UUID> ids = new LinkedHashSet<>();
        for (AppUser u : users.findActiveByRole(tenant, Roles.likePattern(role), PageRequest.of(0, 20))) ids.add(u.getId());
        return ids;
    }
}
