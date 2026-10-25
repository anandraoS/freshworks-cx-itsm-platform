package com.example.cx.core.outbox;

import com.example.cx.events.DomainEvent;
import com.example.cx.events.EventListener;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local-profile dispatcher: hands each event to every {@link EventListener} bean in this JVM (e.g. the notification module when
 * it is embedded). A listener failure is reported to the relay AFTER all listeners ran, so the event is retried
 * (at-least-once) - listeners must therefore be idempotent on eventId, as the platform-events contract requires.
 */
@Component
@Profile("!docker")
public class LocalEventPublisher implements EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LocalEventPublisher.class);
    private final ObjectProvider<EventListener> listeners;

    public LocalEventPublisher(ObjectProvider<EventListener> listeners) { this.listeners = listeners; }

    @Override
    public void publish(DomainEvent event) {
        RuntimeException failure = null;
        List<EventListener> all = listeners.orderedStream().toList();
        for (EventListener l : all) {
            try {
                l.onEvent(event);
            } catch (RuntimeException e) {
                log.warn("Local listener {} failed for event {}: {}", l.getClass().getSimpleName(), event.eventId(), e.toString());
                failure = e;
            }
        }
        if (all.isEmpty()) log.debug("No local listeners registered; event {} ({}) dropped after commit", event.eventId(), event.type());
        if (failure != null) throw failure;
    }
}
