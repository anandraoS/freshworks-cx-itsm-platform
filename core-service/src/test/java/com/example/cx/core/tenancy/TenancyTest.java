package com.example.cx.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cx.core.AbstractApiTest;
import com.example.cx.core.tickets.Priority;
import com.example.cx.core.tickets.Severity;
import com.example.cx.core.tickets.Ticket;
import com.example.cx.core.tickets.TicketType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TenancyTest extends AbstractApiTest {
    @PersistenceContext EntityManager em;
    @Autowired PlatformTransactionManager txm;

    @Test
    void hibernateTenantFilterRestrictsEvenUnscopedQueries() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        createTicket(a.adminToken(), "A1", "LOW");
        createTicket(a.adminToken(), "A2", "LOW");
        createTicket(b.adminToken(), "B1", "LOW");
        TransactionTemplate tx = new TransactionTemplate(txm);
        // a query that FORGETS the tenant predicate still only sees the bound tenant (the filter is the safety net)
        Long forA = TenantContext.callAs(a.id(), () -> tx.execute(s -> em.createQuery("select count(t) from Ticket t", Long.class).getSingleResult()));
        Long forB = TenantContext.callAs(b.id(), () -> tx.execute(s -> em.createQuery("select count(t) from Ticket t", Long.class).getSingleResult()));
        assertThat(forA).isEqualTo(2);
        assertThat(forB).isEqualTo(1);
        Long usersOfA = TenantContext.callAs(a.id(), () -> tx.execute(s -> em.createQuery("select count(u) from AppUser u", Long.class).getSingleResult()));
        assertThat(usersOfA).isEqualTo(1); // only A admin: B admin is invisible although the query has no tenant predicate
        assertThat(TenantContext.current()).isEmpty(); // callAs restored the (empty) binding
    }

    @Test
    void crossTenantWritesAreBlockedAtPersist() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        TransactionTemplate tx = new TransactionTemplate(txm);
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setTenantId(b.id()); // pretends to belong to B
        t.setNumber(99);
        t.setType(TicketType.SUPPORT);
        t.setTitle("x");
        t.setDescription("x");
        t.setPriority(Priority.LOW);
        t.setSeverity(Severity.MINOR);
        t.setRequesterId(UUID.randomUUID());
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> tx.executeWithoutResult(s -> em.persist(t))))
                .hasMessageContaining("Cross-tenant write blocked");
    }

    @Test
    void persistWithoutAnyTenantIsRefused() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> em.persist(t))).hasMessageContaining("without a tenant");
    }

    @Test
    void tenantContextRequireFailsWhenUnbound() {
        TenantContext.clear();
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ticketNumbersAreUniqueAndGapFreeUnderConcurrency() throws Exception {
        Tenant t = newTenant();
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            fs.add(pool.submit((Callable<Long>) () -> {
                go.await();
                return createTicket(t.adminToken(), "Concurrent " + k, "LOW").get("number").asLong();
            }));
        }
        go.countDown();
        Set<Long> numbers = new HashSet<>();
        for (Future<Long> f : fs) numbers.add(f.get());
        pool.shutdown();
        assertThat(numbers).hasSize(n);
        assertThat(numbers).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }
}
