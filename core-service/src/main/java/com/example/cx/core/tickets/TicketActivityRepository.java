package com.example.cx.core.tickets;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Append-only: save + bounded reads only. */
public interface TicketActivityRepository extends Repository<TicketActivity, UUID> {
    TicketActivity save(TicketActivity a);

    List<TicketActivity> findByTenantIdAndTicketIdOrderByCreatedAtDescIdDesc(UUID tenantId, UUID ticketId, Pageable limit);

    List<TicketActivity> findByTenantIdAndTicketIdAndTypeInOrderByCreatedAtDescIdDesc(
            UUID tenantId, UUID ticketId, Collection<String> types, Pageable limit);
}
