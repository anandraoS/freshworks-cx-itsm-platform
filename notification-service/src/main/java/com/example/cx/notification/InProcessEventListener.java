package com.example.cx.notification;

import com.example.cx.events.DomainEvent;
import com.example.cx.events.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local-mode adapter: core-service's in-memory dispatcher calls {@link #onEvent}. Retries in-process using the same
 * {@link RetryPolicy} as the RabbitMQ path and dead-letters (persisted DEAD state) when exhausted. Never throws to the
 * caller: notification problems must not break ticket flows.
 */
public class InProcessEventListener implements EventListener {
    private static final Logger log = LoggerFactory.getLogger(InProcessEventListener.class);

    private final NotificationProcessor processor;
    private final RetryPolicy policy;
    private final long delayMs;

    public InProcessEventListener(NotificationProcessor processor, RetryPolicy policy, long delayMs) {
        this.processor = processor;
        this.policy = policy;
        this.delayMs = delayMs;
    }

    @Override
    public void onEvent(DomainEvent event) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                processor.process(event);
                return;
            } catch (Exception ex) {
                RetryPolicy.Decision d = policy.decide(attempt, ex);
                if (!d.retry()) {
                    log.error("Notification event {} dead-lettered after {} attempt(s): {}", event == null ? null : event.eventId(), attempt, ex.toString());
                    try {
                        if (event != null && event.eventId() != null && event.tenantId() != null) processor.markDead(event, ex);
                    } catch (Exception inner) {
                        log.error("Could not persist DEAD state", inner);
                    }
                    return;
                }
                log.warn("Notification event {} attempt {} failed, retrying: {}", event.eventId(), attempt, ex.toString());
                if (delayMs > 0) {
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }
}
