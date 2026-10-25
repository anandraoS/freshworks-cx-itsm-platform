package com.example.cx.notification;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.NotificationTemplates.Rendered;
import com.example.cx.notification.channel.ChannelSender;
import com.example.cx.notification.store.NotificationStore;
import com.example.cx.notification.store.NotificationStore.Claim;
import com.example.cx.notification.store.NotificationStore.PendingDelivery;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transport-agnostic core: DomainEvent in, notifications + deliveries out. Plain class (no Spring, no RabbitMQ).
 *
 * <p>Stages (each its own short transaction in the store):
 * <ol>
 *   <li>claim the eventId (unique key in notif_processed_event): DONE/DEAD gives a DUPLICATE no-op;</li>
 *   <li>idempotently create notification + PENDING delivery rows (unique keys make re-runs safe);</li>
 *   <li>send every channel not yet SENT; failures are recorded FAILED and rethrown after trying all channels;</li>
 *   <li>mark the event DONE only when all deliveries are SENT.</li>
 * </ol>
 * A retry therefore re-sends only what failed (effectively-once for the simulated channels).
 */
public class NotificationProcessor {
    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final NotificationStore store;
    private final NotificationTemplates templates;
    private final Map<NotificationChannel, ChannelSender> senders = new EnumMap<>(NotificationChannel.class);
    private final List<NotificationChannel> channels;
    private final NotificationMetrics metrics;
    private final RecipientDirectory directory;

    public NotificationProcessor(NotificationStore store, NotificationTemplates templates, List<ChannelSender> senders,
                                 List<NotificationChannel> channels, NotificationMetrics metrics) {
        this(store, templates, senders, channels, metrics, null);
    }

    /** @param directory optional; when present (and able to answer) recipients that are not users of the event's tenant are dropped */
    public NotificationProcessor(NotificationStore store, NotificationTemplates templates, List<ChannelSender> senders,
                                 List<NotificationChannel> channels, NotificationMetrics metrics, RecipientDirectory directory) {
        this.directory = directory;
        this.store = store;
        this.templates = templates;
        senders.forEach(s -> this.senders.put(s.channel(), s));
        this.channels = List.copyOf(channels);
        this.metrics = metrics;
        for (NotificationChannel c : this.channels) {
            if (!this.senders.containsKey(c)) throw new IllegalArgumentException("No sender for channel " + c);
        }
    }

    public ProcessingResult process(DomainEvent e) {
        validate(e);
        Claim claim = store.claim(e);
        if (claim == Claim.DUPLICATE) {
            log.debug("Duplicate event {} ignored", e.eventId());
            metrics.count("duplicate");
            return ProcessingResult.DUPLICATE;
        }
        List<UUID> recipients = tenantMembers(e, recipients(e));
        if (recipients.isEmpty()) {
            store.complete(e.eventId());
            metrics.count("no_recipients");
            return ProcessingResult.NO_RECIPIENTS;
        }
        Rendered rendered = templates.render(e);
        List<PendingDelivery> deliveries = store.createNotifications(e, rendered, recipients, channels);
        List<String> failures = new ArrayList<>();
        Exception first = null;
        for (PendingDelivery d : deliveries) {
            if (d.status() == DeliveryStatus.SENT) continue;
            try {
                senders.get(d.channel()).send(d);
                store.markDelivery(d.deliveryId(), DeliveryStatus.SENT, null);
            } catch (Exception ex) {
                if (first == null) first = ex;
                failures.add(d.channel() + "/" + d.userId());
                store.markDelivery(d.deliveryId(), DeliveryStatus.FAILED, String.valueOf(ex.getMessage()));
            }
        }
        if (first != null) {
            metrics.count("failed");
            throw new NotificationDeliveryException("Delivery failed for " + failures + " of event " + e.eventId(), first);
        }
        store.complete(e.eventId());
        metrics.count("processed");
        return ProcessingResult.PROCESSED;
    }

    /** Drops recipients that are not users of the event's tenant (a forged / buggy event must not reach another tenant's inbox). */
    private List<UUID> tenantMembers(DomainEvent e, List<UUID> candidates) {
        if (directory == null || candidates.isEmpty()) return candidates;
        var members = directory.usersInTenant(e.tenantId(), candidates);
        if (members.isEmpty()) return candidates;   // directory not reachable in this deployment: payload check only
        List<UUID> ok = new ArrayList<>();
        for (UUID u : candidates) {
            if (members.get().contains(u)) ok.add(u);
        }
        int rejected = candidates.size() - ok.size();
        if (rejected > 0) {
            log.warn("Event {} ({}) listed {} recipient(s) that do not belong to tenant {}: dropped", e.eventId(), e.type(), rejected, e.tenantId());
            metrics.count("recipient_rejected");
        }
        return ok;
    }

    /** Terminal failure (retries exhausted or poison): persists DEAD state for audit. */
    public void markDead(DomainEvent e, Throwable cause) {
        store.markDead(e.eventId(), e.tenantId(), e.type(), cause == null ? "unknown" : cause.toString());
        metrics.count("dead");
    }

    static void validate(DomainEvent e) {
        if (e == null) throw new NonRetryableException("null event");
        if (e.eventId() == null) throw new NonRetryableException("event without eventId");
        if (e.tenantId() == null) throw new NonRetryableException("event without tenantId: " + e.eventId());
        if (e.type() == null || e.type().isBlank()) throw new NonRetryableException("event without type: " + e.eventId());
        // Payload tenant consistency: an event whose payload names another tenant than its envelope is forged or corrupt.
        String payloadTenant = e.payload() == null ? null : e.payload().get("tenantId");
        if (payloadTenant != null && !payloadTenant.isBlank() && !payloadTenant.trim().equalsIgnoreCase(e.tenantId().toString())) {
            throw new NonRetryableException("payload tenantId does not match envelope tenantId: " + e.eventId());
        }
    }

    static List<UUID> recipients(DomainEvent e) {
        String raw = e.payload() == null ? null : e.payload().get("recipientUserIds");
        List<UUID> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                UUID id = UUID.fromString(p);
                if (!out.contains(id)) out.add(id);
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring non-UUID recipient in event {}", e.eventId());
            }
        }
        return out;
    }
}
