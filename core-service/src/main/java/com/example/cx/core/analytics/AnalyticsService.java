package com.example.cx.core.analytics;

import com.example.cx.core.cache.CacheService;
import com.example.cx.core.common.ApiException;
import com.example.cx.core.identity.AppUser;
import com.example.cx.core.identity.TenantSettingsService;
import com.example.cx.core.identity.UserRepository;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.SlaStatus;
import com.example.cx.core.tickets.TicketCommentRepository;
import com.example.cx.core.tickets.TicketRepository;
import com.example.cx.core.tickets.TicketStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Dashboard aggregates for MANAGER+.
 *
 * LIVE vs PRECOMPUTED: counts / group-bys (open tickets, by priority, workload, 7-day activity) are LIVE index-backed aggregate
 * queries (cheap: composite indexes lead with tenant_id). The time-window metrics (averages, SLA compliance, trend) read a
 * bounded narrow projection (max {@value #MAX_ROWS} rows, 5 columns) and fold it in memory, which is fine at SMB scale. At larger
 * scale these become a PRECOMPUTED rollup table (hourly/daily per tenant, maintained by the outbox events) - the cache below then
 * sits in front of the rollup. Either way the result is cached per tenant for 60 s (cache-aside) and also evicted on ticket writes.
 */
@Service
public class AnalyticsService {
    private static final int MAX_ROWS = 50_000;
    public static final Duration TTL = Duration.ofSeconds(60);

    public record AgentWorkload(UUID agentId, String name, long openCount) {}

    public record TrendPoint(LocalDate date, long resolved, long created) {}

    public record TenantActivity(long ticketsCreatedLast7d, long commentsLast7d, long activeUsers) {}

    public record Dashboard(long openTickets, Map<String, Long> ticketsByPriority, double avgFirstResponseMinutes,
                            double avgResolutionMinutes, double slaCompliancePercent, long backlog, long reopenedTickets,
                            double aiAcceptanceRatePercent, List<AgentWorkload> agentWorkload, List<TrendPoint> resolutionTrend,
                            TenantActivity tenantActivity) {}

    /** EXTENSION POINT: the AI module registers a bean to supply the real acceptance rate (default 0). */
    public interface AiAcceptanceProvider {
        double acceptanceRatePercent(UUID tenantId, Instant from);
    }

    private final TicketRepository tickets;
    private final TicketCommentRepository comments;
    private final UserRepository users;
    private final CacheService cache;
    private final ObjectProvider<AiAcceptanceProvider> ai;
    private final TenantSettingsService settings;
    private final TransactionTemplate readTx;

    public AnalyticsService(TicketRepository tickets, TicketCommentRepository comments, UserRepository users, CacheService cache,
                            ObjectProvider<AiAcceptanceProvider> ai, TenantSettingsService settings,
                            PlatformTransactionManager txm) {
        this.settings = settings;
        this.tickets = tickets;
        this.comments = comments;
        this.users = users;
        this.cache = cache;
        this.ai = ai;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
    }

    public Dashboard dashboard(int days) {
        if (days < 1 || days > 365) throw ApiException.validation("days", "must be between 1 and 365");
        UUID tenant = TenantContext.require();
        return cache.getOrLoad(tenant, "analytics:dashboard:" + days, TTL, new TypeReference<Dashboard>() {},
                () -> readTx.execute(s -> compute(tenant, days)));
    }

    Dashboard compute(UUID tenant, int days) {
        Instant now = Instant.now();
        Instant from = now.minus(days, ChronoUnit.DAYS);
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);

        long open = tickets.countByTenantIdAndStatusNotIn(tenant, TicketStatus.DONE);
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (Priority p : Priority.values()) byPriority.put(p.name(), 0L);
        for (Object[] row : tickets.openByPriority(tenant, TicketStatus.DONE)) byPriority.put(((Priority) row[0]).name(), (Long) row[1]);
        long backlog = tickets.countByTenantIdAndStatusNotInAndCreatedAtBefore(tenant, TicketStatus.DONE, now.minus(7, ChronoUnit.DAYS));

        List<TicketAgg> rows = tickets.aggregates(tenant, from, PageRequest.of(0, MAX_ROWS));
        double avgFirst = rows.stream().filter(r -> r.createdAt().isAfter(from) && r.firstRespondedAt() != null)
                .mapToLong(r -> Duration.between(r.createdAt(), r.firstRespondedAt()).toMinutes()).average().orElse(0);
        List<TicketAgg> resolved = rows.stream().filter(r -> r.resolvedAt() != null && !r.resolvedAt().isBefore(from)).toList();
        double avgRes = resolved.stream().mapToLong(r -> Duration.between(r.createdAt(), r.resolvedAt()).toMinutes()).average().orElse(0);
        double compliance = resolved.isEmpty() ? 100.0
                : 100.0 * resolved.stream().filter(r -> r.slaStatus() == SlaStatus.MET).count() / resolved.size();
        long reopened = rows.stream().filter(r -> r.createdAt().isAfter(from) && r.reopenCount() > 0).count();

        TreeMap<LocalDate, long[]> trend = new TreeMap<>();
        ZoneId zone = tenantZone();   // day buckets follow the tenant's calendar days, not UTC days
        LocalDate today = LocalDate.ofInstant(now, zone);
        for (int i = days - 1; i >= 0; i--) trend.put(today.minusDays(i), new long[2]);
        for (TicketAgg r : rows) {
            long[] c = trend.get(LocalDate.ofInstant(r.createdAt(), zone));
            if (c != null) c[1]++;
            if (r.resolvedAt() != null) {
                long[] s = trend.get(LocalDate.ofInstant(r.resolvedAt(), zone));
                if (s != null) s[0]++;
            }
        }
        List<TrendPoint> points = trend.entrySet().stream().map(e -> new TrendPoint(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();

        List<Object[]> load = tickets.openByAssignee(tenant, TicketStatus.DONE, PageRequest.of(0, 50));
        Map<UUID, String> names = load.isEmpty() ? Map.of()
                : users.findByTenantIdAndIdIn(tenant, load.stream().map(r -> (UUID) r[0]).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        List<AgentWorkload> workload = new ArrayList<>();
        for (Object[] r : load) workload.add(new AgentWorkload((UUID) r[0], names.getOrDefault((UUID) r[0], "Unknown"), (Long) r[1]));

        long activeUsers = users.countActiveSince(tenant, weekAgo);
        TenantActivity activity = new TenantActivity(tickets.countByTenantIdAndCreatedAtGreaterThanEqual(tenant, weekAgo),
                comments.countByTenantIdAndCreatedAtGreaterThanEqual(tenant, weekAgo), activeUsers);
        AiAcceptanceProvider provider = ai.getIfAvailable();
        double aiRate = provider == null ? 0.0 : provider.acceptanceRatePercent(tenant, from);
        return new Dashboard(open, byPriority, round(avgFirst), round(avgRes), round(compliance), backlog, reopened, round(aiRate),
                workload, points, activity);
    }

    private ZoneId tenantZone() {
        try {
            return ZoneId.of(settings.calendar().timezone());
        } catch (DateTimeException | NullPointerException e) {
            return ZoneOffset.UTC;
        }
    }

    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }
}
