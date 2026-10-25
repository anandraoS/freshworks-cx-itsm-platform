package com.example.cx.core.tickets;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCategoryRepository extends JpaRepository<TicketCategory, UUID> {
    Optional<TicketCategory> findByIdAndTenantId(UUID id, UUID tenantId);
    List<TicketCategory> findByTenantIdOrderByNameAsc(UUID tenantId, Pageable limit);
    List<TicketCategory> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
}
