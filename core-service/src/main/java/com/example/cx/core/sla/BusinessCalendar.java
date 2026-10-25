package com.example.cx.core.sla;

import com.example.cx.core.identity.TenantSettingsService;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Business-hours arithmetic in the tenant's time zone (working days + daily window). Pure and immutable.
 * All results are exact in minutes and DST-safe because windows are built per local date with ZonedDateTime.
 */
public final class BusinessCalendar {
    private static final int MAX_ITERATIONS = 20_000;

    private final ZoneId zone;
    private final LocalTime start;
    private final LocalTime end;
    private final Set<DayOfWeek> days;

    public BusinessCalendar(ZoneId zone, LocalTime start, LocalTime end, Set<DayOfWeek> days) {
        this.zone = zone;
        this.start = start;
        this.end = end;
        this.days = Set.copyOf(days);
    }

    public static BusinessCalendar of(TenantSettingsService.CalendarSettings s) {
        return new BusinessCalendar(ZoneId.of(s.timezone()), LocalTime.parse(s.start()), LocalTime.parse(s.end()),
                TenantSettingsService.parseDays(s.days()));
    }

    private boolean degenerate() { return days.isEmpty() || !start.isBefore(end); }

    /** The instant that is {@code minutes} of working time after {@code from} (rolls forward to the next opening if closed). */
    public Instant addBusinessMinutes(Instant from, long minutes) {
        if (minutes <= 0) return from;
        if (degenerate()) return from.plus(minutes, ChronoUnit.MINUTES);
        ZonedDateTime t = from.atZone(zone);
        long remaining = minutes;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            LocalDate d = t.toLocalDate();
            if (!days.contains(d.getDayOfWeek())) { t = nextDayStart(d); continue; }
            ZonedDateTime open = d.atTime(start).atZone(zone);
            ZonedDateTime close = d.atTime(end).atZone(zone);
            if (t.isBefore(open)) t = open;
            if (!t.isBefore(close)) { t = nextDayStart(d); continue; }
            long available = ChronoUnit.MINUTES.between(t, close);
            if (remaining <= available) return t.plusMinutes(remaining).toInstant();
            remaining -= available;
            t = nextDayStart(d);
        }
        throw new IllegalStateException("Business-minute computation exceeded iteration bound");
    }

    /** Working minutes inside [from, to). */
    public long businessMinutesBetween(Instant from, Instant to) {
        if (!to.isAfter(from)) return 0;
        if (degenerate()) return Duration.between(from, to).toMinutes();
        ZonedDateTime a = from.atZone(zone);
        ZonedDateTime b = to.atZone(zone);
        long total = 0;
        LocalDate d = a.toLocalDate();
        LocalDate last = b.toLocalDate();
        for (int i = 0; i < MAX_ITERATIONS && !d.isAfter(last); i++, d = d.plusDays(1)) {
            if (!days.contains(d.getDayOfWeek())) continue;
            ZonedDateTime open = d.atTime(start).atZone(zone);
            ZonedDateTime close = d.atTime(end).atZone(zone);
            ZonedDateTime s = open.isBefore(a) ? a : open;
            ZonedDateTime e = close.isAfter(b) ? b : close;
            if (e.isAfter(s)) total += ChronoUnit.MINUTES.between(s, e);
        }
        return total;
    }

    private ZonedDateTime nextDayStart(LocalDate d) { return d.plusDays(1).atTime(start).atZone(zone); }
}
