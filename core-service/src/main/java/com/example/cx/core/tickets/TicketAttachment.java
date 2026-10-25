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

/** Synthetic metadata only; no bytes are stored. */
@Entity
@Table(name = "ticket_attachment")
@Filter(name = TenantOwned.FILTER, condition = TenantOwned.FILTER_CONDITION)
@Getter
@Setter
@NoArgsConstructor
public class TicketAttachment extends TenantOwned {
    @Id
    private UUID id;
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;
    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
