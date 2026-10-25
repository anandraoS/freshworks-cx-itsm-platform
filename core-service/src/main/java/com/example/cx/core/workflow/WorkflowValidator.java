package com.example.cx.core.workflow;

import com.example.cx.core.security.Role;
import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.Severity;
import com.example.cx.core.tickets.TicketStatus;
import com.example.cx.core.tickets.TicketType;
import com.example.cx.core.workflow.WorkflowModel.Action;
import com.example.cx.core.workflow.WorkflowModel.Condition;
import com.example.cx.core.workflow.WorkflowModel.Rule;
import com.example.cx.core.workflow.WorkflowModel.Transition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Validates a workflow against the constrained model. Returns human-readable errors (empty list = valid). */
public final class WorkflowValidator {
    public static final int MAX_RULES = 50, MAX_CONDITIONS = 10, MAX_ACTIONS = 10, MAX_TRANSITIONS = 60, MAX_TEXT = 200;
    public static final Set<String> OPS = Set.of("EQ", "NE", "GT", "LT", "IN", "CONTAINS");
    public static final Set<String> ACTIONS = Set.of("ASSIGN_TO_ROLE_LEAST_LOADED", "SET_PRIORITY", "ESCALATE", "NOTIFY", "ADD_TAG");
    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,29}$");

    private enum Kind { ENUM, NUMBER, TEXT, TAGS }

    private record FieldSpec(Kind kind, Set<String> ops, Predicate<String> enumCheck) {}

    private static final Map<String, FieldSpec> FIELDS = Map.of(
            "priority", new FieldSpec(Kind.ENUM, Set.of("EQ", "NE", "GT", "LT", "IN"), s -> isEnum(Priority.class, s)),
            "severity", new FieldSpec(Kind.ENUM, Set.of("EQ", "NE", "GT", "LT", "IN"), s -> isEnum(Severity.class, s)),
            "type", new FieldSpec(Kind.ENUM, Set.of("EQ", "NE", "IN"), s -> isEnum(TicketType.class, s)),
            "status", new FieldSpec(Kind.ENUM, Set.of("EQ", "NE", "IN"), s -> isEnum(TicketStatus.class, s)),
            "escalationLevel", new FieldSpec(Kind.NUMBER, Set.of("EQ", "NE", "GT", "LT", "IN"), null),
            "reopenCount", new FieldSpec(Kind.NUMBER, Set.of("EQ", "NE", "GT", "LT", "IN"), null),
            "title", new FieldSpec(Kind.TEXT, Set.of("EQ", "NE", "CONTAINS"), null),
            "description", new FieldSpec(Kind.TEXT, Set.of("EQ", "NE", "CONTAINS"), null),
            "category", new FieldSpec(Kind.TEXT, Set.of("EQ", "NE", "IN", "CONTAINS"), null),
            "tags", new FieldSpec(Kind.TAGS, Set.of("CONTAINS", "IN"), null));

    private WorkflowValidator() {}

    public static List<String> validate(List<Rule> rules, List<Transition> transitions) {
        List<String> errors = new ArrayList<>();
        List<Rule> rs = rules == null ? List.of() : rules;
        if (rs.size() > MAX_RULES) errors.add("rules: at most " + MAX_RULES + " rules");
        for (int i = 0; i < Math.min(rs.size(), MAX_RULES); i++) validateRule("rules[" + i + "]", rs.get(i), errors);
        validateTransitions(transitions == null ? List.of() : transitions, errors);
        return errors;
    }

    private static void validateRule(String p, Rule r, List<String> errors) {
        if (r == null) { errors.add(p + ": rule must not be null"); return; }
        if (r.name() == null || r.name().isBlank() || r.name().length() > 120) errors.add(p + ".name: required, max 120 chars");
        if (r.safeWhen().size() > MAX_CONDITIONS) errors.add(p + ".when: at most " + MAX_CONDITIONS + " conditions");
        if (r.safeThen().isEmpty()) errors.add(p + ".then: at least one action is required");
        if (r.safeThen().size() > MAX_ACTIONS) errors.add(p + ".then: at most " + MAX_ACTIONS + " actions");
        for (int i = 0; i < Math.min(r.safeWhen().size(), MAX_CONDITIONS); i++) validateCondition(p + ".when[" + i + "]", r.safeWhen().get(i), errors);
        for (int i = 0; i < Math.min(r.safeThen().size(), MAX_ACTIONS); i++) validateAction(p + ".then[" + i + "]", r.safeThen().get(i), errors);
    }

    private static void validateCondition(String p, Condition c, List<String> errors) {
        if (c == null) { errors.add(p + ": condition must not be null"); return; }
        FieldSpec spec = c.field() == null ? null : FIELDS.get(c.field());
        if (spec == null) { errors.add(p + ".field: unsupported field '" + clip(c.field()) + "' (allowed: " + new java.util.TreeSet<>(FIELDS.keySet()) + ")"); return; }
        if (c.op() == null || !OPS.contains(c.op())) { errors.add(p + ".op: unsupported operator '" + clip(c.op()) + "' (allowed: EQ, NE, GT, LT, IN, CONTAINS)"); return; }
        if (!spec.ops().contains(c.op())) { errors.add(p + ".op: " + c.op() + " is not allowed for field " + c.field()); return; }
        if (c.value() == null) { errors.add(p + ".value: required"); return; }
        List<Object> values = "IN".equals(c.op()) ? asList(c.value()) : List.of(c.value());
        if ("IN".equals(c.op()) && (values.isEmpty() || values.size() > 20)) { errors.add(p + ".value: IN needs 1-20 values"); return; }
        for (Object v : values) {
            switch (spec.kind()) {
                case ENUM -> { if (!(v instanceof String s) || !spec.enumCheck().test(s)) errors.add(p + ".value: '" + clip(String.valueOf(v)) + "' is not a valid " + c.field()); }
                case NUMBER -> { if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 1000) errors.add(p + ".value: must be an integer 0-1000"); }
                case TEXT, TAGS -> {
                    if (!(v instanceof String s) || s.isBlank() || s.length() > MAX_TEXT) errors.add(p + ".value: non-blank text up to " + MAX_TEXT + " chars required");
                    else if (looksLikeExpression(s)) errors.add(p + ".value: expression syntax is not allowed");
                }
            }
        }
    }

    private static void validateAction(String p, Action a, List<String> errors) {
        if (a == null || a.action() == null || !ACTIONS.contains(a.action())) {
            errors.add(p + ".action: unsupported action '" + clip(a == null ? null : a.action()) + "' (allowed: " + new java.util.TreeSet<>(ACTIONS) + ")");
            return;
        }
        Map<String, Object> params = a.safeParams();
        Set<String> allowedKeys = switch (a.action()) {
            case "ASSIGN_TO_ROLE_LEAST_LOADED" -> Set.of("role");
            case "SET_PRIORITY" -> Set.of("priority");
            case "NOTIFY" -> Set.of("role", "message");
            case "ADD_TAG" -> Set.of("tag");
            default -> Set.of("reason");
        };
        for (String k : params.keySet()) if (!allowedKeys.contains(k)) errors.add(p + ".params." + clip(k) + ": unknown parameter for " + a.action());
        switch (a.action()) {
            case "ASSIGN_TO_ROLE_LEAST_LOADED" -> {
                Object role = params.get("role");
                if (role != null && !(role instanceof String rs && isEnum(Role.class, rs) && !"CUSTOMER".equals(rs)))
                    errors.add(p + ".params.role: must be AGENT, MANAGER or ADMIN");
            }
            case "SET_PRIORITY" -> {
                if (!(params.get("priority") instanceof String s) || !isEnum(Priority.class, s)) errors.add(p + ".params.priority: LOW|MEDIUM|HIGH|URGENT required");
            }
            case "NOTIFY" -> {
                Object role = params.get("role");
                if (role != null && !(role instanceof String rs && isEnum(Role.class, rs) && !"CUSTOMER".equals(rs)))
                    errors.add(p + ".params.role: must be AGENT, MANAGER or ADMIN");
                if (!(params.get("message") instanceof String m) || m.isBlank() || m.length() > MAX_TEXT) errors.add(p + ".params.message: required, max " + MAX_TEXT + " chars");
                else if (looksLikeExpression(m)) errors.add(p + ".params.message: expression syntax is not allowed");
            }
            case "ADD_TAG" -> {
                if (!(params.get("tag") instanceof String s) || !TAG.matcher(s).matches()) errors.add(p + ".params.tag: 1-30 chars of a-z, 0-9, hyphen");
            }
            default -> {
                Object reason = params.get("reason");
                if (reason != null && (!(reason instanceof String s) || s.length() > MAX_TEXT)) errors.add(p + ".params.reason: text up to " + MAX_TEXT + " chars");
            }
        }
    }

    private static void validateTransitions(List<Transition> ts, List<String> errors) {
        if (ts.size() > MAX_TRANSITIONS) { errors.add("transitions: at most " + MAX_TRANSITIONS); return; }
        Set<String> seen = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < ts.size(); i++) {
            Transition t = ts.get(i);
            String p = "transitions[" + i + "]";
            if (t == null || !isEnum(TicketStatus.class, t.from()) || !isEnum(TicketStatus.class, t.to())) { errors.add(p + ": from/to must be valid statuses"); ok = false; continue; }
            if (t.from().equals(t.to())) { errors.add(p + ": from and to must differ"); ok = false; }
            if (!seen.add(t.from() + ">" + t.to())) { errors.add(p + ": duplicate transition"); ok = false; }
        }
        if (ok && !ts.isEmpty() && !reachable(ts, TicketStatus.NEW, TicketStatus.RESOLVED))
            errors.add("transitions: a ticket must be able to get from NEW to RESOLVED");
    }

    private static boolean reachable(List<Transition> ts, TicketStatus from, TicketStatus to) {
        Set<TicketStatus> seen = EnumSet.of(from);
        Deque<TicketStatus> q = new ArrayDeque<>(List.of(from));
        while (!q.isEmpty()) {
            TicketStatus cur = q.poll();
            if (cur == to) return true;
            for (Transition t : ts) {
                if (t.from().equals(cur.name())) {
                    TicketStatus n = TicketStatus.valueOf(t.to());
                    if (seen.add(n)) q.add(n);
                }
            }
        }
        return false;
    }

    static List<Object> asList(Object v) {
        if (v instanceof List<?> l) return new ArrayList<>(l);
        if (v instanceof String s) return new ArrayList<>(java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(x -> (Object) x).toList());
        return new ArrayList<>(List.of(v));
    }

    /** Defence in depth: values are only ever compared as text, but we refuse anything that looks like SpEL/EL/templating. */
    static boolean looksLikeExpression(String s) {
        return s.contains("${") || s.contains("#{") || s.contains("T(") || s.contains("{{") || s.contains("<%");
    }

    private static <E extends Enum<E>> boolean isEnum(Class<E> type, String s) {
        if (s == null) return false;
        try { Enum.valueOf(type, s); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private static String clip(String s) { return s == null ? "null" : s.length() > 40 ? s.substring(0, 40) + "..." : s; }
}
