package com.example.cx.events;

/**
 * Tiny in-process subscription contract. In the {@code local} runtime core-service's in-memory
 * dispatcher calls every registered {@code EventListener} synchronously (or on its own executor);
 * in the {@code docker} runtime the same logic is fed from RabbitMQ instead.
 *
 * <p>Implementations MUST be idempotent on {@link DomainEvent#eventId()} (at-least-once delivery).
 */
@FunctionalInterface
public interface EventListener {
    void onEvent(DomainEvent event);
}
