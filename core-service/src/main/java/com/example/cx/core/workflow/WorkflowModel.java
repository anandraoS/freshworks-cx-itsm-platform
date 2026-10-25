package com.example.cx.core.workflow;

import com.example.cx.core.tickets.TicketStateMachine;
import com.example.cx.core.tickets.TicketStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The constrained workflow rule model. It is DATA, never code: conditions compare ticket facts with a fixed operator set and
 * actions come from a fixed vocabulary. There is no script engine, no expression language and no reflection anywhere on this path.
 */
public final class WorkflowModel {
    private WorkflowModel() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Condition(String field, String op, Object value) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String action, Map<String, Object> params) {
        public Map<String, Object> safeParams() { return params == null ? Map.of() : params; }
    }

    public record Rule(String name, List<Condition> when, List<Action> then) {
        public List<Condition> safeWhen() { return when == null ? List.of() : when; }
        public List<Action> safeThen() { return then == null ? List.of() : then; }
    }

    public record Transition(String from, String to) {}

    public record Definition(List<Rule> rules, List<Transition> transitions) {
        public Definition {
            rules = rules == null ? List.of() : List.copyOf(rules);
            transitions = transitions == null ? List.of() : List.copyOf(transitions);
        }

        /** Effective transition table: the explicit transitions when present, otherwise the contract defaults. */
        public Map<TicketStatus, Set<TicketStatus>> transitionTable() {
            if (transitions.isEmpty()) return TicketStateMachine.DEFAULT;
            Map<TicketStatus, Set<TicketStatus>> m = new EnumMap<>(TicketStatus.class);
            for (TicketStatus s : TicketStatus.values()) m.put(s, EnumSet.noneOf(TicketStatus.class));
            for (Transition t : transitions) m.get(TicketStatus.valueOf(t.from())).add(TicketStatus.valueOf(t.to()));
            return m;
        }
    }

    /** The built-in definition used when a tenant has no usable active workflow: no rules, default transitions. */
    public static Definition builtInDefault() { return new Definition(List.of(), List.of()); }
}
