package com.example.cx.core.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-tenant sequence (next number to hand out). Only changed by {@link TicketNumberAllocator} (atomic UPDATE in its own short transaction). */
@Entity
@Table(name = "ticket_counter")
@Getter
@Setter
@NoArgsConstructor
public class TicketCounter {
    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;
    @Column(name = "next_number", nullable = false)
    private long nextNumber = 1;

    public TicketCounter(UUID tenantId) { this.tenantId = tenantId; }

    public long take() { return nextNumber++; }
}
