package com.example.cx.core.tickets;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketCounterRepository extends JpaRepository<TicketCounter, UUID> {
    /**
     * Atomic increment (one UPDATE, takes the row lock). Only used by {@link TicketNumberAllocator} inside its own tiny transaction
     * so the lock is held for microseconds, never for the duration of a ticket-creating business transaction.
     * @return rows updated (0 = the tenant has no counter row yet)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TicketCounter c set c.nextNumber = c.nextNumber + 1 where c.tenantId = :t")
    int increment(@Param("t") UUID tenantId);

    @Query("select c.nextNumber from TicketCounter c where c.tenantId = :t")
    Long currentNext(@Param("t") UUID tenantId);
}
