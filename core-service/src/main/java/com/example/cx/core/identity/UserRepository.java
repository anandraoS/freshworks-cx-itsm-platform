package com.example.cx.core.identity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every finder is explicitly tenant-scoped (Hibernate filters do not cover find-by-id). */
public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByTenantIdAndEmail(UUID tenantId, String email);
    Optional<AppUser> findByIdAndTenantId(UUID id, UUID tenantId);
    List<AppUser> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    @Query("select u from AppUser u where u.tenantId = :t and (:activeOnly = false or u.active = true) "
            + "and (lower(u.displayName) like :q or lower(u.email) like :q) order by u.displayName asc, u.id asc")
    List<AppUser> search(@Param("t") UUID tenantId, @Param("activeOnly") boolean activeOnly, @Param("q") String qLike,
                         Pageable limit);

    @Query("select u from AppUser u where u.tenantId = :t and u.active = true and u.rolesCsv like :rolePattern order by u.id")
    List<AppUser> findActiveByRole(@Param("t") UUID tenantId, @Param("rolePattern") String rolePattern, Pageable limit);

    @Query("select count(u) from AppUser u where u.tenantId = :t and u.active = true and u.rolesCsv like '%,ADMIN,%'")
    long countActiveAdmins(@Param("t") UUID tenantId);

    @Query("select count(u) from AppUser u where u.tenantId = :t and u.lastLoginAt >= :from")
    long countActiveSince(@Param("t") UUID tenantId, @Param("from") Instant from);

    @Modifying
    @Query("update AppUser u set u.lastLoginAt = :at where u.id = :id and u.tenantId = :t")
    int touchLogin(@Param("id") UUID id, @Param("t") UUID tenantId, @Param("at") Instant at);
}
