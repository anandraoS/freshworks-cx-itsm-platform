package com.example.cx.core.workflow;

import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.Roles;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.security.Role;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.Severity;
import com.example.cx.core.tickets.Ticket;
import com.example.cx.core.tickets.TicketRepository;
import com.example.cx.core.tickets.TicketStatus;
import com.example.cx.core.workflow.WorkflowModel.Action;
import com.example.cx.core.workflow.WorkflowModel.Condition;
import com.example.cx.core.workflow.WorkflowModel.Definition;
import com.example.cx.core.workflow.WorkflowModel.Rule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Interprets the constrained rule model against a ticket. Pure data interpretation: a switch over a fixed set of operators and
 * actions; no scripting, no expression evaluation, no reflection. All matching rules fire once per trigger in declaration order
 * (no re-evaluation loop), capped at {@value #MAX_ACTIONS_PER_RUN} actions, so a workflow can never loop or run away.
 * The caller owns the transaction; this class only mutates the ticket entity and reports what it did.
 */
@Component
public class WorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    static final int MAX_ACTIONS_PER_RUN = 20;
    private static final int MAX_ESCALATION = 5;

    /** What an action did; the caller turns these into activity rows and outbox events. */
    public record Applied(String action, String detail, UUID assigneeId, boolean escalated, List<UUID> notifyRecipients) {}

    private final UserRepository users;
    private final TicketRepository tickets;

    public WorkflowEngine(UserRepository users, TicketRepository tickets) {
        this.users = users;
        this.tickets = tickets;
    }

    public List<Applied> apply(Ticket t, String categoryName, Definition def) {
        List<Applied> out = new ArrayList<>();
        for (Rule rule : def.rules()) {
            if (out.size() >= MAX_ACTIONS_PER_RUN) break;
            if (!matches(rule, t, categoryName)) continue;
            for (Action a : rule.safeThen()) {
                if (out.size() >= MAX_ACTIONS_PER_RUN) break;
                try {
                    Applied applied = execute(rule.name(), a, t);
                    if (applied != null) out.add(applied);
                } catch (RuntimeException e) { // one broken action must not break ticket handling
                    log.warn("Workflow action {} in rule '{}' failed: {}", a.action(), rule.name(), e.toString());
                }
            }
        }
        return out;
    }

    // ---- conditions -------------------------------------------------------------------------------------------------

    boolean matches(Rule rule, Ticket t, String categoryName) {
        Map<String, Object> facts = facts(t, categoryName);
        for (Condition c : rule.safeWhen()) if (!test(c, facts)) return false;
        return true;
    }

    static Map<String, Object> facts(Ticket t, String categoryName) {
        Map<String, Object> f = new HashMap<>();
        f.put("priority", t.getPriority().name());
        f.put("severity", t.getSeverity().name());
        f.put("type", t.getType().name());
        f.put("status", t.getStatus().name());
        f.put("escalationLevel", t.getEscalationLevel());
        f.put("reopenCount", t.getReopenCount());
        f.put("title", t.getTitle());
        f.put("description", t.getDescription());
        f.put("category", categoryName == null ? "" : categoryName);
        f.put("tags", t.tagList());
        return f;
    }

    public static boolean test(Condition c, Map<String, Object> facts) {
        Object actual = facts.get(c.field());
        if (actual == null) return false;
        return switch (c.op()) {
            case "EQ" -> eq(actual, c.value());
            case "NE" -> !eq(actual, c.value());
            case "GT" -> compare(c.field(), actual, c.value()) > 0;
            case "LT" -> compare(c.field(), actual, c.value()) < 0;
            case "IN" -> WorkflowValidator.asList(c.value()).stream().anyMatch(v -> eq(actual, v));
            case "CONTAINS" -> contains(actual, c.value());
            default -> false;
        };
    }

    private static boolean eq(Object actual, Object expected) {
        if (actual instanceof List<?> l) return l.stream().anyMatch(x -> eq(x, expected));
        if (actual instanceof Number a && expected instanceof Number b) return a.doubleValue() == b.doubleValue();
        return String.valueOf(actual).equalsIgnoreCase(String.valueOf(expected));
    }

    private static int compare(String field, Object actual, Object expected) {
        try {
            return switch (field) {
                case "priority" -> Integer.compare(Priority.valueOf(String.valueOf(actual)).rank(), Priority.valueOf(String.valueOf(expected)).rank());
                case "severity" -> Integer.compare(Severity.valueOf(String.valueOf(actual)).rank(), Severity.valueOf(String.valueOf(expected)).rank());
                default -> Double.compare(((Number) actual).doubleValue(), ((Number) expected).doubleValue());
            };
        } catch (RuntimeException e) {
            return 0; // never matches GT/LT when malformed
        }
    }

    private static boolean contains(Object actual, Object expected) {
        String needle = String.valueOf(expected);
        if (actual instanceof List<?> l) return l.stream().anyMatch(x -> String.valueOf(x).equalsIgnoreCase(needle));
        return String.valueOf(actual).toLowerCase().contains(needle.toLowerCase());
    }

    // ---- actions ----------------------------------------------------------------------------------------------------

    private Applied execute(String ruleName, Action a, Ticket t) {
        Map<String, Object> p = a.safeParams();
        UUID tenant = TenantContext.require();
        switch (a.action()) {
            case "SET_PRIORITY": {
                Priority np = Priority.valueOf((String) p.get("priority"));
                if (np == t.getPriority()) return null;
                Priority old = t.getPriority();
                t.setPriority(np);
                return new Applied("SET_PRIORITY", "rule '" + ruleName + "': priority " + old + " -> " + np, null, false, List.of());
            }
            case "ESCALATE": {
                if (t.getEscalationLevel() >= MAX_ESCALATION) return null;
                t.setEscalationLevel(t.getEscalationLevel() + 1);
                return new Applied("ESCALATE", "rule '" + ruleName + "': escalated to level " + t.getEscalationLevel(), null, true, List.of());
            }
            case "ADD_TAG": {
                String tag = (String) p.get("tag");
                List<String> tags = t.tagList();
                if (tags.contains(tag) || tags.size() >= 20) return null;
                tags.add(tag);
                t.setTagList(tags);
                return new Applied("ADD_TAG", "rule '" + ruleName + "': tag '" + tag + "' added", null, false, List.of());
            }
            case "ASSIGN_TO_ROLE_LEAST_LOADED": {
                if (t.getAssigneeId() != null) return null; // never overrides a human decision
                Role role = Role.valueOf((String) p.getOrDefault("role", "AGENT"));
                List<AppUser> candidates = users.findActiveByRole(tenant, Roles.likePattern(role), PageRequest.of(0, 100));
                if (candidates.isEmpty()) return null;
                Map<UUID, Long> load = new HashMap<>();
                for (Object[] row : tickets.openCountsFor(tenant, candidates.stream().map(AppUser::getId).toList(), TicketStatus.DONE))
                    load.put((UUID) row[0], (Long) row[1]);
                AppUser best = candidates.stream()
                        .min(java.util.Comparator.comparingLong((AppUser u) -> load.getOrDefault(u.getId(), 0L)).thenComparing(AppUser::getId))
                        .orElseThrow();
                t.setAssigneeId(best.getId());
                return new Applied("ASSIGN_TO_ROLE_LEAST_LOADED", "rule '" + ruleName + "': assigned to " + best.getDisplayName()
                        + " (open load " + load.getOrDefault(best.getId(), 0L) + ")", best.getId(), false, List.of());
            }
            case "NOTIFY": {
                String message = (String) p.get("message");
                List<UUID> recipients = new ArrayList<>();
                if (p.get("role") != null) {
                    users.findActiveByRole(tenant, Roles.likePattern(Role.valueOf((String) p.get("role"))), PageRequest.of(0, 20))
                            .forEach(u -> recipients.add(u.getId()));
                } else if (t.getAssigneeId() != null) {
                    recipients.add(t.getAssigneeId());
                }
                return new Applied("NOTIFY", "rule '" + ruleName + "': " + message, null, false, recipients);
            }
            default:
                return null;
        }
    }
}
