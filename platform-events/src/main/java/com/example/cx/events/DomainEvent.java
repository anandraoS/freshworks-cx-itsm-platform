package com.example.cx.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned event envelope published via the transactional outbox to RabbitMQ.
 * Delivery is at-least-once; consumers MUST de-duplicate on {@code eventId}.
 *
 * @param eventId       unique id (idempotency key for consumers)
 * @param type          routing key, e.g. {@code ticket.created}, {@code ticket.assigned}, {@code ticket.escalated}, {@code ticket.sla.breached}, {@code ai.suggestion.decided}
 * @param schemaVersion payload schema version (increase on breaking change)
 * @param tenantId      owning tenant (every message is tenant-aware)
 * @param correlationId request correlation id for tracing
 * @param occurredAt    event time
 * @param payload       flat key/value payload (ticketId, ticketNumber, title, recipientUserIds (comma list), actorId, ...)
 */
public record DomainEvent(
        UUID eventId,
        String type,
        int schemaVersion,
        UUID tenantId,
        String correlationId,
        Instant occurredAt,
        Map<String, String> payload) {

    public static final String EXCHANGE = "cx.events";
    public static final String DLX = "cx.events.dlx";
    public static final String NOTIFICATION_QUEUE = "cx.notifications";
    public static final String NOTIFICATION_RETRY_QUEUE = "cx.notifications.retry";
    public static final String NOTIFICATION_DLQ = "cx.notifications.dlq";
}
