package com.example.cx.core.kb;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbCategoryRepository extends JpaRepository<KbCategory, UUID> {
    Optional<KbCategory> findByIdAndTenantId(UUID id, UUID tenantId);
    List<KbCategory> findByTenantIdOrderByNameAsc(UUID tenantId, Pageable limit);
    List<KbCategory> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);
}
