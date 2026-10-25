package com.example.cx.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.cx.core.AbstractApiTest;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.events.DomainEvent;
import com.example.cx.events.EventListener;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(OutboxTest.Capture.class)
@TestPropertySource(properties = {"cx.outbox.max-attempts=3", "cx.outbox.backoff-base-seconds=2"})
class OutboxTest extends AbstractApiTest {

    /** Stands in for the notification module: records events and can be told to fail. */
    static class Collector implements EventListener {
        final List<DomainEvent> received = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();

        @Override
        public void onEvent(DomainEvent event) {
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) throw new IllegalStateException("simulated consumer outage");
            received.add(event);
        }
    }

    @TestConfiguration
    static class Capture {
        @Bean Collector collector() { return new Collector(); }
    }

    @Autowired Collector collector;
    @Autowired OutboxService outbox;
    @Autowired OutboxRelay relay;
    @Autowired OutboxRepository repo;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;

    @BeforeEach
    void reset() {
        collector.received.clear();
        collector.failuresRemaining.set(0);
        jdbc.update("update outbox_event set status = 'SENT', sent_at = current_timestamp where status in ('PENDING','PROCESSING')");
    }

    private long count(UUID tenant, String type) {
        return jdbc.queryForObject("select count(*) from outbox_event where tenant_id = ? and event_type = ?", Long.class, tenant, type);
    }

    private void makeDueNow(UUID eventId) {
        jdbc.update("update outbox_event set next_attempt_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), eventId);
    }

    @Test
    void publishRequiresAnActiveTransaction() throws Exception {
        Tenant t = newTenant();
        assertThatThrownBy(() -> TenantContext.runAs(t.id(), () -> outbox.publish("ticket.created", Map.of("ticketId", "x"))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void eventIsAtomicWithTheBusinessTransaction() throws Exception {
        Tenant t = newTenant();
        TransactionTemplate tx = new TransactionTemplate(txm);
        // rollback -> nothing is written
        assertThatThrownBy(() -> TenantContext.runAs(t.id(), () -> tx.executeWithoutResult(s -> {
            outbox.publish("ticket.created", Map.of("ticketId", "rolled-back"));
            throw new IllegalStateException("business failure after publish");
        }))).hasMessageContaining("business failure");
        assertThat(count(t.id(), "ticket.created")).isZero();
        // commit -> exactly one PENDING row
        TenantContext.runAs(t.id(), () -> tx.executeWithoutResult(s -> outbox.publish("ticket.created", Map.of("ticketId", "committed"))));
        assertThat(count(t.id(), "ticket.created")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from outbox_event where tenant_id = ?", String.class, t.id())).isEqualTo("PENDING");
    }

    @Test
    void failedBusinessOperationLeavesNoEvent() throws Exception {
        Tenant t = newTenant();
        Map<String, Object> body = new java.util.HashMap<>(ticketBody("Bad category", "LOW"));
        body.put("categoryId", UUID.randomUUID().toString()); // unknown -> 400, transaction rolled back
        var r = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(json.writeValueAsString(body))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where tenant_id = ?", Long.class, t.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ticket where tenant_id = ?", Long.class, t.id())).isZero();
    }

    @Test
    void ticketLifecycleEmitsTheContractEventTypesWithTenantAndCorrelation() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        String customer = userToken(t, "cust", "CUSTOMER");
        UUID agentId = userId(agent);
        var created = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + customer)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Correlation-Id", "corr-abc-123")
                .contentType("application/json").content(json.writeValueAsString(ticketBody("Event source", "LOW")))).andReturn();
        String id = body(created).get("id").asText();
        doPost(agent, "/api/v1/tickets/" + id + "/assign", Map.of("assigneeId", agentId.toString(), "version", 0));
        doPost(agent, "/api/v1/tickets/" + id + "/comments", Map.of("body", "internal only", "visibility", "INTERNAL"));
        doPost(agent, "/api/v1/tickets/" + id + "/comments", Map.of("body", "public reply", "visibility", "PUBLIC"));
        // assign bumped the version to 1, the first public staff reply (first response) to 2
        doPost(agent, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "OPEN", "version", 2));

        assertThat(relay.relayOnce()).isGreaterThanOrEqualTo(5);
        List<DomainEvent> mine = collector.received.stream().filter(e -> e.tenantId().equals(t.id())).toList();
        assertThat(mine).extracting(DomainEvent::type).contains("ticket.created", "ticket.assigned", "ticket.commented", "ticket.status.changed");
        assertThat(mine).allSatisfy(e -> {
            assertThat(e.eventId()).isNotNull();
            assertThat(e.schemaVersion()).isEqualTo(1);
            assertThat(e.payload()).containsKeys("ticketId", "ticketNumber", "title");
        });
        assertThat(mine.stream().map(DomainEvent::eventId).distinct().count()).isEqualTo(mine.size());
        DomainEvent createdEvent = mine.stream().filter(e -> e.type().equals("ticket.created")).findFirst().orElseThrow();
        assertThat(createdEvent.correlationId()).isEqualTo("corr-abc-123"); // propagated from the request header
        UUID customerId = userId(customer);
        // INTERNAL comment is never announced to the requester; the PUBLIC one is
        List<DomainEvent> comments = mine.stream().filter(e -> e.type().equals("ticket.commented")).toList();
        assertThat(comments).hasSize(2);
        for (DomainEvent c : comments) {
            boolean internal = "INTERNAL".equals(c.payload().get("visibility"));
            assertThat(c.payload().get("recipientUserIds").contains(customerId.toString())).isEqualTo(!internal);
        }
        DomainEvent assigned = mine.stream().filter(e -> e.type().equals("ticket.assigned")).findFirst().orElseThrow();
        assertThat(assigned.payload().get("assigneeId")).isEqualTo(agentId.toString());
    }

    @Test
    void relayDeliversOnceAndMarksSent() throws Exception {
        Tenant t = newTenant();
        createTicket(t.adminToken(), "Relay me", "LOW");
        int rel = relay.relayOnce();
        if (rel != 1) System.out.println("DIAG rel=" + rel + " rows=" + jdbc.queryForList("select event_type, status, attempts, next_attempt_at, locked_until, last_error, created_at from outbox_event where tenant_id = ?", t.id()) + " now=" + java.time.Instant.now() + " received=" + collector.received.size());
        assertThat(rel).isEqualTo(1);
        assertThat(collector.received).hasSize(1);
        assertThat(jdbc.queryForObject("select status from outbox_event where tenant_id = ?", String.class, t.id())).isEqualTo("SENT");
        assertThat(relay.relayOnce()).isZero(); // nothing left
        assertThat(collector.received).hasSize(1);
    }

    @Test
    void failuresAreRetriedWithBackoffAndEventuallyDeadLettered() throws Exception {
        Tenant t = newTenant();
        createTicket(t.adminToken(), "Flaky consumer", "LOW");
        UUID eventId = jdbc.queryForObject("select id from outbox_event where tenant_id = ?", UUID.class, t.id());

        collector.failuresRemaining.set(1);
        assertThat(relay.relayOnce()).isZero();
        Map<String, Object> row = jdbc.queryForMap("select status, attempts, next_attempt_at, last_error from outbox_event where id = ?", eventId);
        assertThat(row.get("STATUS")).isEqualTo("PENDING");
        assertThat(((Number) row.get("ATTEMPTS")).intValue()).isEqualTo(1);
        assertThat(row.get("LAST_ERROR").toString()).contains("simulated consumer outage");
        assertThat(relay.relayOnce()).isZero(); // backoff: not due yet (2s base)
        assertThat(collector.received).isEmpty();

        makeDueNow(eventId);
        assertThat(relay.relayOnce()).isEqualTo(1); // consumer recovered -> delivered on attempt 2
        assertThat(collector.received).hasSize(1);
        assertThat(jdbc.queryForObject("select attempts from outbox_event where id = ?", Integer.class, eventId)).isEqualTo(2);

        // permanent failure -> FAILED after max-attempts (3)
        createTicket(t.adminToken(), "Dead letter", "LOW");
        UUID dead = jdbc.queryForObject("select id from outbox_event where tenant_id = ? and status = 'PENDING'", UUID.class, t.id());
        collector.failuresRemaining.set(100);
        for (int i = 0; i < 4; i++) { makeDueNow(dead); relay.relayOnce(); }
        Map<String, Object> d = jdbc.queryForMap("select status, attempts from outbox_event where id = ?", dead);
        assertThat(d.get("STATUS")).isEqualTo("FAILED");
        assertThat(((Number) d.get("ATTEMPTS")).intValue()).isEqualTo(3);
    }

    @Test
    void exactlyOneRelayClaimsARow() throws Exception {
        Tenant t = newTenant();
        createTicket(t.adminToken(), "Contended claim", "LOW");
        UUID id = jdbc.queryForObject("select id from outbox_event where tenant_id = ?", UUID.class, t.id());
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(txm);
        List<Future<Integer>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String node = "node-" + i;
            fs.add(pool.submit((Callable<Integer>) () -> {
                go.await();
                return tx.execute(s -> repo.claim(id, node, Instant.now().plusSeconds(60), Instant.now()));
            }));
        }
        go.countDown();
        int winners = 0;
        for (Future<Integer> f : fs) winners += f.get();
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsReclaimedAfterACrashedRelay() throws Exception {
        Tenant t = newTenant();
        createTicket(t.adminToken(), "Crashed relay", "LOW");
        UUID id = jdbc.queryForObject("select id from outbox_event where tenant_id = ?", UUID.class, t.id());
        // simulate a relay that claimed the row and died
        jdbc.update("update outbox_event set status = 'PROCESSING', locked_by = 'dead-node', locked_until = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(300)), id);
        assertThat(relay.relayOnce()).isZero();          // lease still valid: hands off
        jdbc.update("update outbox_event set locked_until = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), id);
        assertThat(relay.relayOnce()).isEqualTo(1);      // lease expired: re-claimed and delivered
        assertThat(collector.received).hasSize(1);
    }

    @Test
    void ticketCreationAndIdempotencyReplayEmitExactlyOneCreatedEvent() throws Exception {
        Tenant t = newTenant();
        String key = "once-" + UUID.randomUUID();
        String payload = json.writeValueAsString(ticketBody("Only once", "LOW"));
        for (int i = 0; i < 3; i++)
            mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken()).header("Idempotency-Key", key)
                    .contentType("application/json").content(payload)).andReturn();
        assertThat(count(t.id(), "ticket.created")).isEqualTo(1);
        JsonNode list = body(doGet(t.adminToken(), "/api/v1/tickets"));
        assertThat(list.get("totalElements").asInt()).isEqualTo(1);
    }
}
