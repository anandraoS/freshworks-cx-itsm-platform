package com.example.cx.core.tickets;

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

/** Append-only per-ticket history. */
@Entity
@Immutable
@Table(name = "ticket_activity")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class TicketActivity extends TenantOwned {
    public static final String CREATED = "CREATED", UPDATED = "UPDATED", STATUS_CHANGED = "STATUS_CHANGED",
            ASSIGNED = "ASSIGNED", COMMENTED = "COMMENTED", ESCALATED = "ESCALATED", SLA_BREACHED = "SLA_BREACHED",
            WORKFLOW = "WORKFLOW_ACTION", NOTIFIED = "NOTIFIED";

    @Id
    private UUID id;
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;
    @Column(name = "actor_id")
    private UUID actorId;
    @Column(name = "actor_name", length = 120)
    private String actorName;
    @Column(nullable = false, length = 40)
    private String type;
    @Column(length = 1000)
    private String details;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
