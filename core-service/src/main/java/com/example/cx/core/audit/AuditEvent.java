package com.example.cx.core.audit;

import com.example.cx.core.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Immutable;

/** Append-only: @Immutable (Hibernate never issues UPDATE) + repository exposes no update/delete (+ PG trigger in migration-pg). */
@Entity
@Immutable
@Table(name = "audit_event")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent extends TenantOwned {
    @Id
    private UUID id;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "actor_id")
    private UUID actorId;
    @Column(name = "actor_email", length = 200)
    private String actorEmail;
    @Column(nullable = false, length = 60)
    private String action;
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;
    @Column(name = "entity_id", length = 64)
    private String entityId;
    @Column(length = 4000)
    private String details;
    @Column(name = "correlation_id", length = 64)
    private String correlationId;
}
