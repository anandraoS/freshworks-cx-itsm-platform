package com.example.cx.core.sla;

import com.example.cx.core.tickets.SlaStatus;
import com.example.cx.core.tickets.Ticket;
import java.time.Instant;
import java.util.List;

/**
 * Pure SLA status evaluation (no I/O) so it is exhaustively unit-testable.
 *
 * Percent of the SLA budget used is measured in BUSINESS minutes in the tenant calendar, consistently with how due dates are
 * computed: {@code used = (targetMinutes - businessMinutesBetween(now, due)) / targetMinutes}. Because due dates are shifted by the
 * business minutes spent paused on resume, paused time is excluded automatically, and closed hours (nights, weekends) consume
 * nothing. Example: a HIGH ticket (480 business-minute resolution target) created Friday 15:30 has used 90/480 = 18.75% on
 * Sunday 21:30, so it is not AT_RISK (wall-clock arithmetic would wrongly say 75%).
 */
public final class SlaCalculator {
    private SlaCalculator() {}

    /**
     * @param status           computed status
     * @param escalationLevel  number of escalation thresholds crossed (never lower than the ticket current level)
     * @param breachType       "RESOLUTION" / "FIRST_RESPONSE" when {@code status == BREACHED} for an open ticket, else null
     */
    public record Result(SlaStatus status, int escalationLevel, String breachType) {}

    /** Policy targets in business minutes (0 when the ticket has no policy / due date). */
    public record Targets(long firstResponseMinutes, long resolutionMinutes) {}

    public static Result evaluate(Ticket t, List<Integer> thresholds, Instant now, int atRiskPercent,
                                  BusinessCalendar cal, Targets targets) {
        int level = t.getEscalationLevel();
        if (t.getStatus().isDone()) {
            Instant finished = t.getResolvedAt() != null ? t.getResolvedAt() : now;
            boolean late = t.isBreachNotified()
                    || (t.getResolutionDueAt() != null && finished.isAfter(t.getResolutionDueAt()));
            return new Result(late ? SlaStatus.BREACHED : SlaStatus.MET, level, null);
        }
        if (t.getSlaPausedAt() != null) return new Result(SlaStatus.PAUSED, level, null);

        boolean responseOverdue = t.getFirstRespondedAt() == null && t.getFirstResponseDueAt() != null
                && now.isAfter(t.getFirstResponseDueAt());
        boolean resolutionOverdue = t.getResolutionDueAt() != null && now.isAfter(t.getResolutionDueAt());

        double resolutionPct = percentUsed(t.getResolutionDueAt(), now, targets.resolutionMinutes(), cal);
        double responsePct = t.getFirstRespondedAt() == null
                ? percentUsed(t.getFirstResponseDueAt(), now, targets.firstResponseMinutes(), cal) : 0;
        double worst = Math.max(resolutionPct, responsePct);

        int crossed = 0;
        for (int th : thresholds) if (resolutionPct >= th) crossed++;
        level = Math.max(level, crossed);

        if (resolutionOverdue || responseOverdue) {
            return new Result(SlaStatus.BREACHED, level, resolutionOverdue ? "RESOLUTION" : "FIRST_RESPONSE");
        }
        return new Result(worst >= atRiskPercent ? SlaStatus.AT_RISK : SlaStatus.ON_TRACK, level, null);
    }

    /** Elapsed share (0..100+) of {@code targetMinutes} of business time, given the (pause-shifted) due date. */
    static double percentUsed(Instant due, Instant now, long targetMinutes, BusinessCalendar cal) {
        if (due == null) return 0;
        if (targetMinutes <= 0 || !now.isBefore(due)) return 100;
        long remaining = cal.businessMinutesBetween(now, due);
        return Math.max(0, 100.0 * (targetMinutes - remaining) / targetMinutes);
    }
}
