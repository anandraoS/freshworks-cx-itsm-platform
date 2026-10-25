package com.example.cx.notification;

import io.micrometer.core.instrument.MeterRegistry;

/** Thin optional metrics facade: no-op when Micrometer is not wired. */
public class NotificationMetrics {
    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) { this.registry = registry; }

    public void count(String result) {
        if (registry != null) registry.counter("cx.notifications.events", "result", result).increment();
    }
}
