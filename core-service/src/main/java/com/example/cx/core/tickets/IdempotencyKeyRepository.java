package com.example.cx.core.tickets;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
    Optional<IdempotencyKey> findByTenantIdAndUserIdAndKey(UUID tenantId, UUID userId, String key);

    @Modifying
    @Query("delete from IdempotencyKey k where k.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
