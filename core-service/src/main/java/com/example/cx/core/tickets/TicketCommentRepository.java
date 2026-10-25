package com.example.cx.core.tickets;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCommentRepository extends JpaRepository<TicketComment, UUID> {
    List<TicketComment> findByTenantIdAndTicketIdOrderByCreatedAtAscIdAsc(UUID tenantId, UUID ticketId, Pageable limit);

    List<TicketComment> findByTenantIdAndTicketIdAndVisibilityOrderByCreatedAtAscIdAsc(
            UUID tenantId, UUID ticketId, Visibility visibility, Pageable limit);

    long countByTenantIdAndCreatedAtGreaterThanEqual(UUID tenantId, Instant from);
}
