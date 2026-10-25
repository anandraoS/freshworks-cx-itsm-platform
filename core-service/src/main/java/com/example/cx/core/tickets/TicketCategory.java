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
@Table(name = "ticket_category")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class TicketCategory extends TenantOwned {
    @Id
    private UUID id;
    @Column(nullable = false, length = 80)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketType type;
    @Enumerated(EnumType.STRING)
    @Column(name = "default_priority", nullable = false, length = 10)
    private Priority defaultPriority;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
