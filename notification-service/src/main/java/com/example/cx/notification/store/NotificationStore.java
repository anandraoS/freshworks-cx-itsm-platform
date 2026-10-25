package com.example.cx.notification.store;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.DeliveryStatus;
import com.example.cx.notification.NotificationChannel;
import com.example.cx.notification.NotificationTemplates.Rendered;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC persistence for notif_* tables. Every query is tenant-scoped. Each public method is its own
 * short transaction (the processor orchestrates the stages, see {@code NotificationProcessor}).
 */
public class NotificationStore {

    public enum Claim { NEW, RESUME, DUPLICATE }

    public record NotificationRow(UUID id, UUID tenantId, UUID userId, String type, String title, String body,
                                  UUID ticketId, boolean read, OffsetDateTime createdAt) {}

    public record PendingDelivery(UUID deliveryId, UUID notificationId, UUID tenantId, UUID userId,
                                  NotificationChannel channel, DeliveryStatus status, String title, String body) {}

    public record DeliveryRow(UUID id, UUID eventId, UUID userId, String channel, DeliveryStatus status, int attempts, String lastError) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public NotificationStore(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.clock = clock;
    }

    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC); }

    // ---------------------------------------------------------------- idempotency

    /** Claims an event. DONE/DEAD events are duplicates (no-op); PROCESSING ones are resumed (retry). */
    public Claim claim(DomainEvent e) {
        return tx.execute(status -> {
            List<String> existing = jdbc.query("select status from notif_processed_event where event_id = ?",
                    (rs, i) -> rs.getString(1), e.eventId());
            if (!existing.isEmpty()) {
                String st = existing.get(0);
                if ("DONE".equals(st) || "DEAD".equals(st)) return Claim.DUPLICATE;
                jdbc.update("update notif_processed_event set attempts = attempts + 1 where event_id = ?", e.eventId());
                return Claim.RESUME;
            }
            try {
                jdbc.update("insert into notif_processed_event (event_id, tenant_id, event_type, status, attempts, first_seen_at) values (?,?,?,?,?,?)",
                        e.eventId(), e.tenantId(), truncate(e.type(), 120), "PROCESSING", 1, now());
                return Claim.NEW;
            } catch (DuplicateKeyException race) {
                status.setRollbackOnly();   // concurrent consumer inserted first: treat as duplicate
                return Claim.DUPLICATE;
            }
        });
    }

    public void complete(UUID eventId) {
        tx.executeWithoutResult(s -> jdbc.update(
                "update notif_processed_event set status = 'DONE', completed_at = ?, last_error = null where event_id = ?", now(), eventId));
    }

    public String eventStatus(UUID eventId) {
        List<String> r = jdbc.query("select status from notif_processed_event where event_id = ?", (rs, i) -> rs.getString(1), eventId);
        return r.isEmpty() ? null : r.get(0);
    }

    // ---------------------------------------------------------------- creation / delivery

    /** Idempotently creates notification + delivery rows; returns all deliveries of the event for these recipients. */
    public List<PendingDelivery> createNotifications(DomainEvent e, Rendered r, List<UUID> recipients, List<NotificationChannel> channels) {
        return tx.execute(s -> {
            OffsetDateTime ts = now();
            List<PendingDelivery> out = new ArrayList<>();
            for (UUID user : recipients) {
                UUID nid = jdbc.query("select id from notif_notification where event_id = ? and user_id = ? and tenant_id = ?",
                        (rs, i) -> rs.getObject(1, UUID.class), e.eventId(), user, e.tenantId()).stream().findFirst().orElse(null);
                if (nid == null) {
                    nid = UUID.randomUUID();
                    jdbc.update("insert into notif_notification (id, tenant_id, user_id, event_id, type, title, body, ticket_id, is_read, created_at) values (?,?,?,?,?,?,?,?,?,?)",
                            nid, e.tenantId(), user, e.eventId(), truncate(r.category(), 120), truncate(r.title(), 300),
                            truncate(r.body(), 2000), r.ticketId(), false, ts);
                }
                for (NotificationChannel ch : channels) {
                    boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                            "select count(*) > 0 from notif_delivery where event_id = ? and user_id = ? and channel = ?",
                            Boolean.class, e.eventId(), user, ch.name()));
                    if (!exists) {
                        jdbc.update("insert into notif_delivery (id, tenant_id, notification_id, event_id, user_id, channel, status, attempts, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?)",
                                UUID.randomUUID(), e.tenantId(), nid, e.eventId(), user, ch.name(), "PENDING", 0, ts, ts);
                    }
                }
            }
            return jdbc.query("""
                    select d.id, d.notification_id, d.tenant_id, d.user_id, d.channel, d.status, n.title, n.body
                      from notif_delivery d join notif_notification n on n.id = d.notification_id
                     where d.event_id = ? and d.tenant_id = ? order by d.created_at, d.id
                    """, (rs, i) -> new PendingDelivery(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), NotificationChannel.valueOf(rs.getString(5)),
                    DeliveryStatus.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8)), e.eventId(), e.tenantId());
        });
    }

    public void markDelivery(UUID deliveryId, DeliveryStatus status, String error) {
        tx.executeWithoutResult(s -> jdbc.update(
                "update notif_delivery set status = ?, attempts = attempts + 1, last_error = ?, updated_at = ?, sent_at = ? where id = ?",
                status.name(), truncate(error, 1000), now(), status == DeliveryStatus.SENT ? now() : null, deliveryId));
    }

    /** Terminal failure: event DEAD, undelivered deliveries DEAD (or a single event-level DEAD row if none exist). */
    public void markDead(UUID eventId, UUID tenantId, String eventType, String error) {
        tx.executeWithoutResult(s -> {
            OffsetDateTime ts = now();
            int updated = jdbc.update("update notif_processed_event set status = 'DEAD', last_error = ?, completed_at = ? where event_id = ?",
                    truncate(error, 1000), ts, eventId);
            if (updated == 0) {
                jdbc.update("insert into notif_processed_event (event_id, tenant_id, event_type, status, attempts, last_error, first_seen_at, completed_at) values (?,?,?,?,?,?,?,?)",
                        eventId, tenantId, truncate(eventType, 120), "DEAD", 0, truncate(error, 1000), ts, ts);
            }
            int dead = jdbc.update("update notif_delivery set status = 'DEAD', last_error = ?, updated_at = ? where event_id = ? and status <> 'SENT'",
                    truncate(error, 1000), ts, eventId);
            boolean hasAny = Boolean.TRUE.equals(jdbc.queryForObject("select count(*) > 0 from notif_delivery where event_id = ?", Boolean.class, eventId));
            if (dead == 0 && !hasAny) {
                jdbc.update("insert into notif_delivery (id, tenant_id, notification_id, event_id, user_id, channel, status, attempts, last_error, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), tenantId, null, eventId, null, "ALL", "DEAD", 0, truncate(error, 1000), ts, ts);
            }
        });
    }

    public List<DeliveryRow> deliveriesForEvent(UUID eventId) {
        return jdbc.query("select id, event_id, user_id, channel, status, attempts, last_error from notif_delivery where event_id = ? order by created_at, channel",
                (rs, i) -> new DeliveryRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), DeliveryStatus.valueOf(rs.getString(5)), rs.getInt(6), rs.getString(7)), eventId);
    }

    // ---------------------------------------------------------------- inbox queries (tenant AND user scoped)

    public List<NotificationRow> list(UUID tenantId, UUID userId, boolean unreadOnly, int page, int size) {
        String sql = "select id, tenant_id, user_id, type, title, body, ticket_id, is_read, created_at from notif_notification where tenant_id = ? and user_id = ?"
                + (unreadOnly ? " and is_read = false" : "") + " order by created_at desc, id desc limit ? offset ?";
        return jdbc.query(sql, (rs, i) -> new NotificationRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getBoolean(8),
                rs.getObject(9, OffsetDateTime.class)), tenantId, userId, size, (long) page * size);
    }

    public long count(UUID tenantId, UUID userId, boolean unreadOnly) {
        String sql = "select count(*) from notif_notification where tenant_id = ? and user_id = ?" + (unreadOnly ? " and is_read = false" : "");
        Long c = jdbc.queryForObject(sql, Long.class, tenantId, userId);
        return c == null ? 0 : c;
    }

    /** @return false when the id does not exist for this tenant+user (cross-tenant ids look like 404). */
    public boolean markRead(UUID tenantId, UUID userId, UUID id) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select count(*) > 0 from notif_notification where id = ? and tenant_id = ? and user_id = ?", Boolean.class, id, tenantId, userId));
            if (exists) {
                jdbc.update("update notif_notification set is_read = true, read_at = ? where id = ? and tenant_id = ? and user_id = ? and is_read = false",
                        now(), id, tenantId, userId);
            }
            return exists;
        }));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
