package com.example.cx.core.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * System table (deliberately NOT tenant-filtered and not under RLS): the relay claims rows across all tenants. tenant_id is
 * still stored and is part of every published event. Rows are only ever inserted by {@link OutboxService} inside the
 * business transaction.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {
    public static final String PENDING = "PENDING", PROCESSING = "PROCESSING", SENT = "SENT", FAILED = "FAILED";

    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;
    @Column(nullable = false, length = 20000)
    private String payload;
    @Column(nullable = false, length = 12)
    private String status = PENDING;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "locked_by", length = 64)
    private String lockedBy;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "last_error", length = 500)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;
}
