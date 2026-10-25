package com.example.cx.core.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.SlaStatus;
import com.example.cx.core.tickets.Ticket;
import com.example.cx.core.tickets.TicketStatus;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlaCalculatorTest {
    static final Instant NOW = Instant.parse("2024-06-05T12:00:00Z");
    static final List<Integer> TH = List.of(75, 100);

    /** Ticket created {@code createdHoursAgo} ago with resolution due in {@code dueInHours}; first response already given. */
    /** Degenerate (no working days) calendar == wall-clock minutes: keeps the generic tests independent of calendars. */
    static final BusinessCalendar WALL = new BusinessCalendar(ZoneOffset.UTC, LocalTime.of(9, 0), LocalTime.of(17, 0), Set.of());
    static final BusinessCalendar OFFICE = new BusinessCalendar(ZoneOffset.UTC, LocalTime.of(9, 0), LocalTime.of(17, 0),
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

    static SlaCalculator.Result eval(Ticket t) {
        long res = t.getResolutionDueAt() == null ? 0 : Duration.between(t.getCreatedAt(), t.getResolutionDueAt()).toMinutes();
        long first = t.getFirstResponseDueAt() == null ? 0 : Duration.between(t.getCreatedAt(), t.getFirstResponseDueAt()).toMinutes();
        return SlaCalculator.evaluate(t, TH, NOW, 75, WALL, new SlaCalculator.Targets(first, res));
    }

    static Ticket ticket(long createdHoursAgo, long dueInHours, TicketStatus status) {
        Ticket t = new Ticket();
        t.setPriority(Priority.MEDIUM);
        t.setStatus(status);
        t.setCreatedAt(NOW.minus(createdHoursAgo, ChronoUnit.HOURS));
        t.setResolutionDueAt(NOW.plus(dueInHours, ChronoUnit.HOURS));
        t.setFirstResponseDueAt(NOW.minus(createdHoursAgo, ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS));
        t.setFirstRespondedAt(NOW.minus(createdHoursAgo, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES));
        return t;
    }

    @Test
    void onTrackWhenLittleBudgetIsUsed() {
        var r = eval(ticket(2, 8, TicketStatus.OPEN)); // 20% used
        assertThat(r.status()).isEqualTo(SlaStatus.ON_TRACK);
        assertThat(r.escalationLevel()).isZero();
    }

    @Test
    void atRiskAtOrAboveTheThreshold() {
        var r = eval(ticket(8, 2, TicketStatus.OPEN)); // 80% used
        assertThat(r.status()).isEqualTo(SlaStatus.AT_RISK);
        assertThat(r.escalationLevel()).isEqualTo(1); // 75% threshold crossed
    }

    @Test
    void breachedWhenResolutionOverdue() {
        var r = eval(ticket(10, -1, TicketStatus.OPEN));
        assertThat(r.status()).isEqualTo(SlaStatus.BREACHED);
        assertThat(r.breachType()).isEqualTo("RESOLUTION");
        assertThat(r.escalationLevel()).isEqualTo(2); // both thresholds crossed
    }

    @Test
    void breachedWhenFirstResponseOverdue() {
        Ticket t = ticket(3, 20, TicketStatus.OPEN);
        t.setFirstRespondedAt(null); // due 2h ago
        var r = eval(t);
        assertThat(r.status()).isEqualTo(SlaStatus.BREACHED);
        assertThat(r.breachType()).isEqualTo("FIRST_RESPONSE");
    }

    @Test
    void pausedTicketsNeverBreach() {
        Ticket t = ticket(10, -5, TicketStatus.PENDING);
        t.setSlaPausedAt(NOW.minus(1, ChronoUnit.HOURS));
        assertThat(eval(t).status()).isEqualTo(SlaStatus.PAUSED);
    }

    @Test
    void resolvedInTimeIsMetAndLateIsBreached() {
        Ticket ok = ticket(5, 3, TicketStatus.RESOLVED);
        ok.setResolvedAt(NOW.minus(1, ChronoUnit.HOURS));
        assertThat(eval(ok).status()).isEqualTo(SlaStatus.MET);
        Ticket late = ticket(10, -2, TicketStatus.CLOSED);
        late.setResolvedAt(NOW.minus(30, ChronoUnit.MINUTES)); // due was 2h ago
        assertThat(eval(late).status()).isEqualTo(SlaStatus.BREACHED);
        Ticket flagged = ticket(5, 3, TicketStatus.RESOLVED);
        flagged.setResolvedAt(NOW);
        flagged.setBreachNotified(true); // it breached while open, later resolved: stays BREACHED
        assertThat(eval(flagged).status()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    void escalationLevelNeverDecreases() {
        Ticket t = ticket(1, 20, TicketStatus.OPEN);
        t.setEscalationLevel(2);
        assertThat(eval(t).escalationLevel()).isEqualTo(2);
    }

    @Test
    void noDueDatesMeansOnTrack() {
        Ticket t = new Ticket();
        t.setStatus(TicketStatus.OPEN);
        t.setPriority(Priority.LOW);
        t.setCreatedAt(NOW.minus(100, ChronoUnit.DAYS));
        assertThat(eval(t).status()).isEqualTo(SlaStatus.ON_TRACK);
    }

    // ---- business-minute percentages (HIGH policy: 60 first response / 480 resolution, thresholds 75,100; 09:00-17:00 Mon-Fri) ----

    static final SlaCalculator.Targets HIGH = new SlaCalculator.Targets(60, 480);
    static final List<Integer> HIGH_TH = List.of(75, 100);

    static Instant utc(String iso) { return Instant.parse(iso); }

    /** HIGH ticket created at the given time; due dates computed exactly like SlaService.applyDueDates; first response given. */
    static Ticket high(Instant created) {
        Ticket t = new Ticket();
        t.setPriority(Priority.HIGH);
        t.setStatus(TicketStatus.OPEN);
        t.setCreatedAt(created);
        t.setFirstResponseDueAt(OFFICE.addBusinessMinutes(created, HIGH.firstResponseMinutes()));
        t.setResolutionDueAt(OFFICE.addBusinessMinutes(created, HIGH.resolutionMinutes()));
        t.setFirstRespondedAt(created.plusSeconds(60));
        return t;
    }

    static SlaCalculator.Result evalHigh(Ticket t, Instant now) {
        return SlaCalculator.evaluate(t, HIGH_TH, now, 75, OFFICE, HIGH);
    }

    @Test
    void closedHoursDoNotConsumeBudget_fridayEveningTicketIsNotAtRiskOnSunday() {
        // 2024-06-07 is a Friday. Created 15:30 -> 90 business minutes used by close (90/480 = 18.75%), due Monday 15:30.
        Ticket t = high(utc("2024-06-07T15:30:00Z"));
        assertThat(t.getResolutionDueAt()).isEqualTo(utc("2024-06-10T15:30:00Z"));
        Instant sunday = utc("2024-06-09T21:30:00Z");
        assertThat(SlaCalculator.percentUsed(t.getResolutionDueAt(), sunday, 480, OFFICE)).isEqualTo(18.75);
        var r = evalHigh(t, sunday);
        assertThat(r.status()).isEqualTo(SlaStatus.ON_TRACK);
        assertThat(r.escalationLevel()).isZero();
        // the old wall-clock arithmetic would have said (54h / 72h) = 75% -> AT_RISK + escalation level 1
        assertThat(100.0 * Duration.between(t.getCreatedAt(), sunday).toMillis()
                / Duration.between(t.getCreatedAt(), t.getResolutionDueAt()).toMillis()).isEqualTo(75.0);
    }

    @Test
    void thresholdsAreCrossedByBusinessMinutesOnly() {
        Ticket t = high(utc("2024-06-07T15:30:00Z"));
        // Monday 14:30: 90 + 330 = 420 of 480 used = 87.5% -> AT_RISK, 75% escalation crossed
        var late = evalHigh(t, utc("2024-06-10T14:30:00Z"));
        assertThat(late.status()).isEqualTo(SlaStatus.AT_RISK);
        assertThat(late.escalationLevel()).isEqualTo(1);
        // Monday 13:29: 90 + 269 = 359 -> 74.8% -> still ON_TRACK
        assertThat(evalHigh(t, utc("2024-06-10T13:29:00Z")).status()).isEqualTo(SlaStatus.ON_TRACK);
        // after the due date: BREACHED, both thresholds crossed
        var breached = evalHigh(t, utc("2024-06-10T15:31:00Z"));
        assertThat(breached.status()).isEqualTo(SlaStatus.BREACHED);
        assertThat(breached.breachType()).isEqualTo("RESOLUTION");
        assertThat(breached.escalationLevel()).isEqualTo(2);
    }

    @Test
    void pausedBusinessTimeIsExcludedFromPercentUsed() {
        // created Mon 09:00, paused Mon 11:00 (120 used), resumed Tue 11:00 after 360 + 120 = 480 paused business minutes
        Ticket t = high(utc("2024-06-03T09:00:00Z"));
        assertThat(t.getResolutionDueAt()).isEqualTo(utc("2024-06-03T17:00:00Z"));
        t.setResolutionDueAt(OFFICE.addBusinessMinutes(t.getResolutionDueAt(),
                OFFICE.businessMinutesBetween(utc("2024-06-03T11:00:00Z"), utc("2024-06-04T11:00:00Z"))));
        assertThat(t.getResolutionDueAt()).isEqualTo(utc("2024-06-04T17:00:00Z"));
        // Tue 12:00: 120 + 60 = 180 of 480 = 37.5% used; wall-clock (27h of 32h = 84%) would wrongly flag AT_RISK
        assertThat(SlaCalculator.percentUsed(t.getResolutionDueAt(), utc("2024-06-04T12:00:00Z"), 480, OFFICE)).isEqualTo(37.5);
        assertThat(evalHigh(t, utc("2024-06-04T12:00:00Z")).status()).isEqualTo(SlaStatus.ON_TRACK);
    }

    @Test
    void firstResponsePercentUsesBusinessMinutesToo() {
        Ticket t = high(utc("2024-06-07T16:30:00Z")); // response target 60: 30 Friday + 30 Monday -> due Monday 09:30
        t.setFirstRespondedAt(null);
        // Sunday: 30/60 = 50% used, due is in the future -> not AT_RISK (wall-clock would say ~ 93%)
        assertThat(evalHigh(t, utc("2024-06-09T12:00:00Z")).status()).isEqualTo(SlaStatus.ON_TRACK);
        // Monday 09:20 -> 50 of 60 = 83% -> AT_RISK; Monday 09:31 -> BREACHED (FIRST_RESPONSE)
        assertThat(evalHigh(t, utc("2024-06-10T09:20:00Z")).status()).isEqualTo(SlaStatus.AT_RISK);
        assertThat(evalHigh(t, utc("2024-06-10T09:31:00Z")).breachType()).isEqualTo("FIRST_RESPONSE");
    }
}
