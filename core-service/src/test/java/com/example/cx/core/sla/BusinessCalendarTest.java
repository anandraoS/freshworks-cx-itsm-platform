package com.example.cx.core.sla;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {
    static final ZoneId NY = ZoneId.of("America/New_York");
    static final BusinessCalendar CAL = new BusinessCalendar(NY, LocalTime.of(9, 0), LocalTime.of(17, 0),
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

    static Instant ny(int y, int mo, int d, int h, int mi) { return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, NY).toInstant(); }

    @Test
    void addsWithinTheSameDay() {
        // 2024-06-03 is a Monday
        assertThat(CAL.addBusinessMinutes(ny(2024, 6, 3, 10, 0), 60)).isEqualTo(ny(2024, 6, 3, 11, 0));
        assertThat(CAL.addBusinessMinutes(ny(2024, 6, 3, 10, 0), 0)).isEqualTo(ny(2024, 6, 3, 10, 0));
    }

    @Test
    void rollsOverTheWeekend() {
        // Friday 16:30 + 60 min = 30 min Friday + 30 min Monday
        assertThat(CAL.addBusinessMinutes(ny(2024, 6, 7, 16, 30), 60)).isEqualTo(ny(2024, 6, 10, 9, 30));
    }

    @Test
    void startingOutsideBusinessHoursWaitsForOpening() {
        assertThat(CAL.addBusinessMinutes(ny(2024, 6, 8, 12, 0), 30)).isEqualTo(ny(2024, 6, 10, 9, 30)); // Saturday noon
        assertThat(CAL.addBusinessMinutes(ny(2024, 6, 3, 7, 0), 30)).isEqualTo(ny(2024, 6, 3, 9, 30));    // before opening
        assertThat(CAL.addBusinessMinutes(ny(2024, 6, 3, 18, 0), 30)).isEqualTo(ny(2024, 6, 4, 9, 30));   // after closing
    }

    @Test
    void multiDayDurationsEndExactlyAtClosing() {
        // 960 business minutes (two 8h days) from Monday 09:00 ends Tuesday 17:00
        assertThat(CAL.addBusinessMinutes(ny(2024, 6, 3, 9, 0), 960)).isEqualTo(ny(2024, 6, 4, 17, 0));
        assertThat(CAL.addBusinessMinutes(ny(2024, 6, 3, 9, 0), 961)).isEqualTo(ny(2024, 6, 5, 9, 1));
    }

    @Test
    void isDstSafe() {
        // US spring-forward is Sunday 2024-03-10: Friday 16:00 EST + 120 min -> Monday 10:00 EDT
        assertThat(CAL.addBusinessMinutes(ny(2024, 3, 8, 16, 0), 120)).isEqualTo(ny(2024, 3, 11, 10, 0));
        // US fall-back Sunday 2024-11-03
        assertThat(CAL.addBusinessMinutes(ny(2024, 11, 1, 16, 0), 120)).isEqualTo(ny(2024, 11, 4, 10, 0));
    }

    @Test
    void businessMinutesBetweenSkipsClosedTime() {
        assertThat(CAL.businessMinutesBetween(ny(2024, 6, 7, 15, 0), ny(2024, 6, 10, 10, 0))).isEqualTo(120 + 60);
        assertThat(CAL.businessMinutesBetween(ny(2024, 6, 8, 0, 0), ny(2024, 6, 9, 23, 0))).isZero(); // weekend only
        assertThat(CAL.businessMinutesBetween(ny(2024, 6, 3, 9, 0), ny(2024, 6, 3, 17, 0))).isEqualTo(480);
        assertThat(CAL.businessMinutesBetween(ny(2024, 6, 3, 12, 0), ny(2024, 6, 3, 11, 0))).isZero();
    }

    @Test
    void roundTripAddThenMeasure() {
        Instant from = ny(2024, 6, 5, 13, 17);
        for (long m : new long[]{1, 59, 480, 481, 1440, 2999}) {
            Instant to = CAL.addBusinessMinutes(from, m);
            assertThat(CAL.businessMinutesBetween(from, to)).as("minutes=%d", m).isEqualTo(m);
        }
    }

    @Test
    void pauseShiftExtendsDueDateByBusinessTimeOnly() {
        // due Mon 12:00; paused Fri 16:00 -> resumed Mon 10:00 = 60 + 60 business minutes paused -> due shifts by 120
        Instant due = ny(2024, 6, 10, 12, 0);
        long paused = CAL.businessMinutesBetween(ny(2024, 6, 7, 16, 0), ny(2024, 6, 10, 10, 0));
        assertThat(paused).isEqualTo(120);
        assertThat(CAL.addBusinessMinutes(due, paused)).isEqualTo(ny(2024, 6, 10, 14, 0));
    }

    @Test
    void emptyCalendarFallsBackToWallClock() {
        BusinessCalendar none = new BusinessCalendar(ZoneId.of("UTC"), LocalTime.of(9, 0), LocalTime.of(17, 0), Set.of());
        assertThat(none.addBusinessMinutes(Instant.parse("2024-06-03T10:00:00Z"), 90)).isEqualTo(Instant.parse("2024-06-03T11:30:00Z"));
    }
}
