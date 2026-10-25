package com.example.cx.core.tickets;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketAttachmentRepository extends JpaRepository<TicketAttachment, UUID> {
    List<TicketAttachment> findByTenantIdAndTicketIdInOrderByCreatedAtAscIdAsc(UUID tenantId, Collection<UUID> ticketIds);
}
