package com.example.cx.core.tickets;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-tenant ticket numbers: unique per tenant, strictly increasing, NOT guaranteed gap-free.
 *
 * <p>History: numbers used to be taken with {@code SELECT ... FOR UPDATE} on the tenant's {@code ticket_counter} row INSIDE the
 * ticket-creating transaction, so every create of one tenant held the same row lock until its commit (ticket + activity + audit +
 * outbox + idempotency rows). Measured with perf/load-test.mjs this serialised creates per tenant and produced multi-second tails
 * at concurrency 10-20 (see PERFORMANCE-AND-TROUBLESHOOTING-GUIDE.md section 3.1).
 *
 * <p>Now the number is allocated by one atomic {@code UPDATE ... next_number = next_number + 1} in its OWN tiny transaction that
 * commits immediately (lock held for well under a millisecond), BEFORE the business transaction starts. Consequences:
 * <ul>
 *   <li>uniqueness holds: two allocations can never return the same value (the UPDATE serialises them), and the ticket table keeps
 *       its unique (tenant_id, number) constraint as a backstop;</li>
 *   <li>a number is burned (a gap) when the business transaction later fails or loses an idempotency-key race;</li>
 *   <li>the allocator MUST be called while the caller holds no database transaction/connection, otherwise it would need a second
 *       pooled connection per request and could dead-lock the pool under load. {@code TicketCommandService} is its only caller.</li>
 * </ul>
 */
@Component
public class TicketNumberAllocator {
    private final TicketCounterRepository counters;
    private final TransactionTemplate tx;

    public TicketNumberAllocator(TicketCounterRepository counters, PlatformTransactionManager txm) {
        this.counters = counters;
        this.tx = new TransactionTemplate(txm);
    }

    public long next(UUID tenantId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Long n = tx.execute(s -> {
                    if (counters.increment(tenantId) == 0) {
                        counters.saveAndFlush(new TicketCounter(tenantId));   // first ticket of a tenant without a counter row
                        counters.increment(tenantId);
                    }
                    Long after = counters.currentNext(tenantId);
                    return after == null ? null : after - 1;
                });
                if (n != null) return n;
            } catch (DataIntegrityViolationException raceOnFirstInsert) {
                // another request created the counter row first: loop and use the UPDATE path
            }
        }
        throw new IllegalStateException("Could not allocate a ticket number");
    }
}
