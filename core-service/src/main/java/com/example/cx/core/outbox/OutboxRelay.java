package com.example.cx.core.outbox;

import com.example.cx.core.config.CxProperties;
import com.example.cx.events.DomainEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the outbox and hands events to the {@link EventPublisher}.
 *
 * Safe claim: each due row is claimed with a compare-and-set UPDATE (see OutboxRepository#claim) that also sets a lease
 * ({@code locked_until}); only the winner publishes. A relay that crashes mid-publish leaves the row PROCESSING; after the lease
 * expires another relay re-claims it (hence at-least-once delivery, consumers de-duplicate on eventId).
 * Failure: attempts + exponential backoff (base * 2^(attempts-1), capped at 5 min); after {@code max-attempts} the row is FAILED
 * (dead) and a metric/log is emitted. The publish call itself is never inside a database transaction.
 */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final OutboxRepository repo;
    private final OutboxService outbox;
    private final EventPublisher publisher;
    private final CxProperties props;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final String nodeId = "relay-" + UUID.randomUUID().toString().substring(0, 8);

    public OutboxRelay(OutboxRepository repo, OutboxService outbox, EventPublisher publisher, CxProperties props,
                       MeterRegistry meters, PlatformTransactionManager txm) {
        this.repo = repo;
        this.outbox = outbox;
        this.publisher = publisher;
        this.props = props;
        this.meters = meters;
        this.tx = new TransactionTemplate(txm);
    }

    @Scheduled(fixedDelayString = "${cx.outbox.poll-ms:1000}", initialDelay = 2000)
    public void poll() {
        if (!props.outbox().relayEnabled()) return;
        try {
            relayOnce();
        } catch (RuntimeException e) {
            log.warn("Outbox poll failed: {}", e.toString());
        }
    }

    /** One bounded relay pass. @return number of events delivered */
    public int relayOnce() {
        Instant now = Instant.now();
        List<UUID> due = repo.findDueIds(now, PageRequest.of(0, props.outbox().batchSize()));
        int delivered = 0;
        for (UUID id : due) {
            Integer claimed = tx.execute(s -> repo.claim(id, nodeId, now.plusSeconds(props.outbox().lockSeconds()), Instant.now()));
            if (claimed == null || claimed != 1) continue; // another relay owns it
            OutboxEvent row = repo.findById(id).orElse(null);
            if (row == null) continue;
            DomainEvent event;
            try {
                event = outbox.fromJson(row.getPayload());
            } catch (Exception e) {
                fail(id, "unreadable payload", true);
                continue;
            }
            try {
                publisher.publish(event);
                tx.executeWithoutResult(s -> repo.findById(id).ifPresent(r -> {
                    r.setStatus(OutboxEvent.SENT);
                    r.setSentAt(Instant.now());
                    r.setLockedBy(null);
                    r.setLockedUntil(null);
                    r.setLastError(null);
                }));
                meters.counter("cx.outbox.published", "type", event.type()).increment();
                delivered++;
            } catch (Exception e) {
                fail(id, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()), false);
            }
        }
        return delivered;
    }

    private void fail(UUID id, String error, boolean permanent) {
        tx.executeWithoutResult(s -> repo.findById(id).ifPresent(r -> {
            String trimmed = error.length() > 480 ? error.substring(0, 480) : error;
            r.setLastError(trimmed);
            r.setLockedBy(null);
            r.setLockedUntil(null);
            if (permanent || r.getAttempts() >= props.outbox().maxAttempts()) {
                r.setStatus(OutboxEvent.FAILED);
                meters.counter("cx.outbox.failed", "type", r.getEventType()).increment();
                log.error("Outbox event {} ({}) moved to FAILED after {} attempt(s): {}", id, r.getEventType(), r.getAttempts(), trimmed);
            } else {
                long delay = Math.min(MAX_BACKOFF_SECONDS,
                        (long) props.outbox().backoffBaseSeconds() << Math.min(10, Math.max(0, r.getAttempts() - 1)));
                r.setStatus(OutboxEvent.PENDING);
                r.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(delay)));
                meters.counter("cx.outbox.retried", "type", r.getEventType()).increment();
            }
        }));
    }
}
