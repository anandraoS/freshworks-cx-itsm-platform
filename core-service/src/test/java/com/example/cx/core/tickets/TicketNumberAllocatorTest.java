package com.example.cx.core.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The per-tenant number allocator that replaced the long-held SELECT ... FOR UPDATE on ticket_counter. */
class TicketNumberAllocatorTest extends AbstractApiTest {
    @Autowired TicketNumberAllocator allocator;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentAllocationsAreUniqueAndContiguousWhenNothingFails() throws Exception {
        Tenant t = newTenant();
        int threads = 16, perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit((Callable<List<Long>>) () -> {
                go.await();
                List<Long> mine = new ArrayList<>();
                for (int k = 0; k < perThread; k++) mine.add(allocator.next(t.id()));
                return mine;
            }));
        }
        go.countDown();
        Set<Long> all = new HashSet<>();
        for (Future<List<Long>> f : fs) all.addAll(f.get());
        pool.shutdown();

        assertThat(all).hasSize(threads * perThread);                     // no duplicates
        assertThat(all).allMatch(n -> n >= 1 && n <= threads * perThread); // 1..400, no gaps without failures
        assertThat(jdbc.queryForObject("select next_number from ticket_counter where tenant_id = ?", Long.class, t.id()))
                .isEqualTo((long) threads * perThread + 1);
    }

    @Test
    void tenantsHaveIndependentSequencesAndAMissingCounterRowIsCreated() throws Exception {
        Tenant a = newTenant(), b = newTenant();
        assertThat(allocator.next(a.id())).isEqualTo(1);
        assertThat(allocator.next(a.id())).isEqualTo(2);
        assertThat(allocator.next(b.id())).isEqualTo(1);

        UUID ghost = a.id();
        jdbc.update("delete from ticket_counter where tenant_id = ?", ghost);   // counter row missing: allocator recreates it
        assertThat(allocator.next(ghost)).isEqualTo(1);
        assertThat(allocator.next(ghost)).isEqualTo(2);
    }

    @Test
    void aFailedCreateBurnsANumberButNumbersStayUniqueAndIncreasing() throws Exception {
        Tenant t = newTenant();
        long first = createTicket(t.adminToken(), "first", "LOW").get("number").asLong();
        // unknown category -> 400 after the number was allocated: the documented gap
        var bad = mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/tickets"), t.adminToken())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type", "SUPPORT", "title", "x", "description", "y", "priority", "LOW",
                        "severity", "MINOR", "categoryId", UUID.randomUUID().toString())))).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        long next = createTicket(t.adminToken(), "second", "LOW").get("number").asLong();

        assertThat(first).isEqualTo(1);
        assertThat(next).isGreaterThan(first).isEqualTo(3);   // 2 was burned by the failed request
        assertThat(jdbc.queryForObject("select count(distinct number) = count(*) from ticket where tenant_id = ?", Boolean.class, t.id())).isTrue();
    }
}
