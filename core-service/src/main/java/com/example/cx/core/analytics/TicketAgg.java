package com.example.cx.core.analytics;

import com.example.cx.core.tickets.SlaStatus;
import java.time.Instant;

/** Narrow projection (5 columns, no entity) used by the dashboard aggregation. */
public record TicketAgg(Instant createdAt, Instant firstRespondedAt, Instant resolvedAt, SlaStatus slaStatus, int reopenCount) {}
