package com.example.cx.core.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.cx.core.cache.CacheService;
import com.example.cx.core.identity.TenantSettingsService;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.tickets.SlaStatus;
import com.example.cx.core.tickets.TicketCommentRepository;
import com.example.cx.core.tickets.TicketRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** The resolution-trend day buckets follow the TENANT's calendar days, not UTC days. */
class AnalyticsTimeZoneTest {
    private final UUID tenant = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private AnalyticsService service(String timezone, List<TicketAgg> rows) {
        TicketRepository tickets = mock(TicketRepository.class);
        when(tickets.aggregates(any(), any(), any())).thenReturn(rows);
        TenantSettingsService settings = mock(TenantSettingsService.class);
        when(settings.calendar()).thenReturn(new TenantSettingsService.CalendarSettings(timezone, "09:00", "17:00",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")));
        return new AnalyticsService(tickets, mock(TicketCommentRepository.class), mock(UserRepository.class), mock(CacheService.class),
                mock(ObjectProvider.class), settings, mock(PlatformTransactionManager.class));
    }

    @Test
    void bucketsUseTheTenantTimeZoneNotUtc() {
        ZoneId kiritimati = ZoneId.of("Pacific/Kiritimati"); // UTC+14: local 00:30 is always the PREVIOUS day in UTC
        LocalDate localToday = LocalDate.now(kiritimati);
        Instant createdLocalMidnightish = ZonedDateTime.of(localToday, LocalTime.of(0, 30), kiritimati).toInstant();
        assertThat(LocalDate.ofInstant(createdLocalMidnightish, ZoneOffset.UTC)).isEqualTo(localToday.minusDays(1)); // sanity
        Instant resolved = createdLocalMidnightish.plusSeconds(3600);
        var row = new TicketAgg(createdLocalMidnightish, null, resolved, SlaStatus.MET, 0);

        var trend = service("Pacific/Kiritimati", List.of(row)).compute(tenant, 7).resolutionTrend();
        assertThat(trend).hasSize(7);
        assertThat(trend.get(6).date()).isEqualTo(localToday);
        assertThat(trend.get(6).created()).isEqualTo(1);
        assertThat(trend.get(6).resolved()).isEqualTo(1);
        assertThat(trend.get(5).created()).isZero();

        // the same row under a UTC tenant lands in the previous (UTC) day bucket
        var utcTrend = service("UTC", List.of(row)).compute(tenant, 7).resolutionTrend();
        LocalDate utcDay = LocalDate.ofInstant(createdLocalMidnightish, ZoneOffset.UTC);
        assertThat(utcTrend.stream().filter(p -> p.date().equals(utcDay)).findFirst().orElseThrow().created()).isEqualTo(1);
    }

    @Test
    void invalidTimeZoneFallsBackToUtcInsteadOfFailing() {
        var trend = service("Not/AZone", List.of()).compute(tenant, 3).resolutionTrend();
        assertThat(trend).hasSize(3);
        assertThat(trend.get(2).date()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }
}
