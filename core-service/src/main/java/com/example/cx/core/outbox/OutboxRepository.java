package com.example.cx.core.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Due rows: PENDING whose backoff elapsed, or PROCESSING whose lease expired (a crashed relay). Oldest first. */
    @Query("select o.id from OutboxEvent o where (o.status = 'PENDING' and o.nextAttemptAt <= :now) "
            + "or (o.status = 'PROCESSING' and o.lockedUntil < :now) order by o.createdAt asc, o.id asc")
    List<UUID> findDueIds(@Param("now") Instant now, Pageable limit);

    /**
     * Compare-and-set claim: exactly one relay (thread or node) gets updatedRows == 1 for a given row. This is the portable
     * equivalent of SELECT ... FOR UPDATE SKIP LOCKED: competing relays skip rows someone else already owns.
     */
    @Modifying
    @Query("update OutboxEvent o set o.status = 'PROCESSING', o.lockedBy = :by, o.lockedUntil = :until, o.attempts = o.attempts + 1 "
            + "where o.id = :id and ((o.status = 'PENDING' and o.nextAttemptAt <= :now) "
            + "or (o.status = 'PROCESSING' and o.lockedUntil < :now))")
    int claim(@Param("id") UUID id, @Param("by") String by, @Param("until") Instant until, @Param("now") Instant now);

    long countByStatus(String status);

    long countByTenantIdAndEventType(UUID tenantId, String eventType);

    /** Oldest-first ids of SENT rows older than {@code before}: the bounded input of the purge job. */
    @Query("select o.id from OutboxEvent o where o.status = 'SENT' and o.sentAt < :before order by o.sentAt asc, o.id asc")
    List<UUID> findSentIdsBefore(@Param("before") Instant before, Pageable limit);

    @Modifying
    @Query("delete from OutboxEvent o where o.status = 'SENT' and o.id in :ids")
    int deleteSentByIds(@Param("ids") List<UUID> ids);
}
