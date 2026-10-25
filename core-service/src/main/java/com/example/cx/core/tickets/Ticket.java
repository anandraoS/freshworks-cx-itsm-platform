package com.example.cx.core.tickets;

import com.example.cx.core.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Filter;

/**
 * Concurrency: {@code @Version} gives optimistic locking (stale write -> 409 TICKET_VERSION_CONFLICT).
 * {@code @DynamicUpdate}: only changed columns are written, so the SLA job (which updates sla_* columns with bulk UPDATEs
 * that do not bump the version) and a user edit never overwrite each other's columns.
 * References to users / category are plain ids (composite FKs in the DB keep them in-tenant); display names are resolved in
 * one batched query per page, which is what keeps list endpoints free of N+1.
 */
@Entity
@Table(name = "ticket")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class Ticket extends TenantOwned {
    @Id
    private UUID id;
    @Column(nullable = false)
    private long number;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketType type;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 8000)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status = TicketStatus.NEW;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;
    @Column(name = "category_id")
    private UUID categoryId;
    /** Stored as ",a,b," (leading/trailing comma) so LIKE '%,a,%' is exact; avoids a join table (N+1). */
    @Column(nullable = false, length = 500)
    private String tags = ",";
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;
    @Column(name = "assignee_id")
    private UUID assigneeId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "first_response_due_at")
    private Instant firstResponseDueAt;
    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;
    @Column(name = "first_responded_at")
    private Instant firstRespondedAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "sla_status", nullable = false, length = 12)
    private SlaStatus slaStatus = SlaStatus.ON_TRACK;
    @Column(name = "sla_paused_at")
    private Instant slaPausedAt;
    @Column(name = "sla_checked_at")
    private Instant slaCheckedAt;
    @Column(name = "breach_notified", nullable = false)
    private boolean breachNotified;
    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;
    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;
    @Version
    private Long version;

    public List<String> tagList() {
        List<String> out = new ArrayList<>();
        Arrays.stream(tags.split(",")).filter(s -> !s.isBlank()).forEach(out::add);
        return out;
    }

    public void setTagList(List<String> list) {
        if (list == null || list.isEmpty()) { tags = ","; return; }
        tags = "," + String.join(",", list.stream().map(String::toLowerCase).distinct().toList()) + ",";
    }
}
