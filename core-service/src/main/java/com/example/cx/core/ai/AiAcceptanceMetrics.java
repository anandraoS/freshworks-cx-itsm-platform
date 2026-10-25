package com.example.cx.core.ai;

import com.example.cx.core.analytics.AnalyticsService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Feeds the dashboard KPI {@code aiAcceptanceRatePercent}: decided suggestions in the window, (APPROVED + EDITED_APPROVED) / all decided.
 * Pending suggestions are not counted (undecided is not "rejected"); no decisions => 0. Called by AnalyticsService inside its tenant-bound read transaction.
 */
@Component
public class AiAcceptanceMetrics implements AnalyticsService.AiAcceptanceProvider {
    private final AiSuggestionRepository repo;

    public AiAcceptanceMetrics(AiSuggestionRepository repo) { this.repo = repo; }

    @Override
    public double acceptanceRatePercent(UUID tenantId, Instant from) {
        long accepted = repo.countDecided(tenantId, List.of(SuggestionStatus.APPROVED, SuggestionStatus.EDITED_APPROVED), from);
        long rejected = repo.countDecided(tenantId, List.of(SuggestionStatus.REJECTED), from);
        long total = accepted + rejected;
        return total == 0 ? 0.0 : 100.0 * accepted / total;
    }
}
