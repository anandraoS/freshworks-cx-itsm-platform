package com.example.cx.core.tickets;

import com.example.cx.core.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "ticket_comment")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class TicketComment extends TenantOwned {
    @Id
    private UUID id;
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;
    @Column(name = "author_id", nullable = false)
    private UUID authorId;
    @Column(nullable = false, length = 8000)
    private String body;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Visibility visibility;
    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
