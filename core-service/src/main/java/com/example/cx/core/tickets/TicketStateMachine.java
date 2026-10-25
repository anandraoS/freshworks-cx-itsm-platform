package com.example.cx.core.tickets;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Ticket status transitions. The built-in table is the default from the API contract; a tenant's ACTIVE workflow version may
 * replace it (see WorkflowService#allowedTransitions). Pure and stateless so it is trivially unit-testable.
 */
public final class TicketStateMachine {
    public static final Map<TicketStatus, Set<TicketStatus>> DEFAULT = new EnumMap<>(TicketStatus.class);

    static {
        for (TicketStatus s : TicketStatus.values()) DEFAULT.put(s, EnumSet.noneOf(TicketStatus.class));
        allow(TicketStatus.NEW, TicketStatus.OPEN);
        allow(TicketStatus.OPEN, TicketStatus.PENDING, TicketStatus.ON_HOLD, TicketStatus.RESOLVED);
        allow(TicketStatus.PENDING, TicketStatus.OPEN, TicketStatus.RESOLVED);
        allow(TicketStatus.ON_HOLD, TicketStatus.OPEN);
        allow(TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.REOPENED);
        allow(TicketStatus.CLOSED, TicketStatus.REOPENED);
        allow(TicketStatus.REOPENED, TicketStatus.OPEN, TicketStatus.RESOLVED);
    }

    private TicketStateMachine() {}

    private static void allow(TicketStatus from, TicketStatus... to) {
        DEFAULT.get(from).addAll(Set.of(to));
    }

    public static boolean canTransition(TicketStatus from, TicketStatus to) {
        return DEFAULT.get(from).contains(to);
    }

    /** @param table the effective transition table (default or from the active workflow) */
    public static boolean canTransition(Map<TicketStatus, Set<TicketStatus>> table, TicketStatus from, TicketStatus to) {
        Set<TicketStatus> allowed = table.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static Set<TicketStatus> next(TicketStatus from) { return DEFAULT.get(from); }
}
