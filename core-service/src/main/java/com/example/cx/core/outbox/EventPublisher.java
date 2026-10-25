package com.example.cx.core.outbox;

import com.example.cx.events.DomainEvent;

/**
 * EXTENSION POINT: delivery of an event to the outside world. Called ONLY by the outbox relay (never inside a business
 * transaction). Implementations: {@code LocalEventPublisher} (local profile, in-process listeners) and
 * {@code RabbitEventPublisher} (docker profile, exchange cx.events). Throw to make the relay retry with backoff.
 * Delivery is at-least-once: consumers de-duplicate on {@link DomainEvent#eventId()}.
 */
public interface EventPublisher {
    void publish(DomainEvent event);
}
