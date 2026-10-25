package com.example.cx.core.tickets;

import java.util.EnumSet;
import java.util.Set;

public enum TicketStatus {
    NEW, OPEN, PENDING, ON_HOLD, RESOLVED, CLOSED, REOPENED;

    /** Statuses that count as "done" (not open work). */
    public static final Set<TicketStatus> DONE = EnumSet.of(RESOLVED, CLOSED);
    /** SLA clock is paused while waiting on the customer / a third party. */
    public static final Set<TicketStatus> SLA_PAUSING = EnumSet.of(PENDING, ON_HOLD);

    public boolean isDone() { return DONE.contains(this); }
}
