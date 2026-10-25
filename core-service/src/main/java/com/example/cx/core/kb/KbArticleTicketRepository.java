package com.example.cx.core.kb;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbArticleTicketRepository extends JpaRepository<KbArticleTicket, UUID> {
    List<KbArticleTicket> findByTenantIdAndArticleIdInOrderByCreatedAtAsc(UUID tenantId, Collection<UUID> articleIds);
    boolean existsByTenantIdAndArticleIdAndTicketId(UUID tenantId, UUID articleId, UUID ticketId);
}
