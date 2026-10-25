package com.example.cx.core.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class TicketApiTest extends AbstractApiTest {

    @Test
    void createReadUpdateLifecycle() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        String customer = userToken(t, "cust", "CUSTOMER");

        JsonNode created = createTicket(customer, "Printer broken", "LOW");
        assertThat(created.get("status").asText()).isEqualTo("NEW");
        assertThat(created.get("number").asLong()).isEqualTo(1);
        assertThat(created.get("requester").get("displayName").asText()).isEqualTo("cust");
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.get("resolutionDueAt").isNull()).isFalse();
        String id = created.get("id").asText();

        JsonNode second = createTicket(customer, "Second", "LOW");
        assertThat(second.get("number").asLong()).isEqualTo(2); // per-tenant numbering

        // agent updates; version is enforced
        Map<String, Object> upd = Map.of("title", "Printer broken (floor 3)", "description", "d", "priority", "MEDIUM",
                "severity", "MAJOR", "tags", List.of("hardware"), "version", 0);
        MvcResult ok = doPut(agent, "/api/v1/tickets/" + id, upd);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("version").asLong()).isEqualTo(1);
        assertThat(body(ok).get("priority").asText()).isEqualTo("MEDIUM");

        MvcResult stale = doPut(agent, "/api/v1/tickets/" + id, upd); // still version 0
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(stale).get("code").asText()).isEqualTo("TICKET_VERSION_CONFLICT");

        // state machine via API
        MvcResult bad = doPost(agent, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "RESOLVED", "version", 1));
        assertThat(bad.getResponse().getStatus()).isEqualTo(422); // NEW -> RESOLVED not allowed
        assertThat(body(bad).get("code").asText()).isEqualTo("INVALID_TRANSITION");
        MvcResult open = doPost(agent, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "OPEN", "version", 1));
        assertThat(open.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(open).get("status").asText()).isEqualTo("OPEN");

        // history
        JsonNode activity = body(doGet(agent, "/api/v1/tickets/" + id + "/activity"));
        assertThat(activity.findValuesAsText("type")).contains("CREATED", "UPDATED", "STATUS_CHANGED");
    }

    @Test
    void pauseAndResumeSla() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        JsonNode created = createTicket(agent, "SLA pause", "MEDIUM");
        String id = created.get("id").asText();
        JsonNode open = body(doPost(agent, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "OPEN", "version", 0)));
        JsonNode pending = body(doPost(agent, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "PENDING", "version", open.get("version").asLong())));
        assertThat(pending.get("slaStatus").asText()).isEqualTo("PAUSED");
        JsonNode back = body(doPost(agent, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "OPEN", "version", pending.get("version").asLong())));
        assertThat(back.get("slaStatus").asText()).isNotEqualTo("PAUSED");
        JsonNode done = body(doPost(agent, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "RESOLVED", "version", back.get("version").asLong())));
        assertThat(done.get("slaStatus").asText()).isIn("MET", "BREACHED");
        assertThat(done.get("resolvedAt").isNull()).isFalse();
    }

    @Test
    void validationErrorsUseContractSchema() throws Exception {
        Tenant t = newTenant();
        MvcResult r = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SUPPORT\",\"title\":\"\",\"description\":\"x\",\"priority\":\"LOW\",\"severity\":\"MINOR\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode e = body(r);
        assertThat(e.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(e.get("status").asInt()).isEqualTo(400);
        assertThat(e.get("path").asText()).isEqualTo("/api/v1/tickets");
        assertThat(e.get("correlationId").asText()).isNotBlank();
        assertThat(e.get("fieldErrors").get(0).get("field").asText()).isEqualTo("title");

        // missing Idempotency-Key
        MvcResult noKey = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(ticketBody("x", "LOW")))).andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(noKey).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        // unauthenticated
        assertThat(mvc.perform(get("/api/v1/tickets")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void idempotencyKeyReplaysOriginalResponseAndRejectsDifferentBody() throws Exception {
        Tenant t = newTenant();
        String key = "key-" + UUID.randomUUID();
        String bodyA = json.writeValueAsString(ticketBody("Idempotent ticket", "LOW"));
        MvcResult first = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(bodyA)).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(bodyA)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(body(replay).get("id").asText()).isEqualTo(body(first).get("id").asText());
        assertThat(body(doGet(t.adminToken(), "/api/v1/tickets?size=100")).get("totalElements").asInt()).isEqualTo(1); // no duplicate

        MvcResult different = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ticketBody("A different ticket", "LOW")))).andReturn();
        assertThat(different.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(different).get("code").asText()).isEqualTo("DUPLICATE_SUBMISSION");
    }

    @Test
    void concurrentCreatesWithSameKeyYieldOneTicket() throws Exception {
        Tenant t = newTenant();
        String key = "race-" + UUID.randomUUID();
        String bodyA = json.writeValueAsString(ticketBody("Raced ticket", "LOW"));
        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit((Callable<Integer>) () -> {
                go.await();
                return mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(bodyA)).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> f : results) assertThat(f.get()).isEqualTo(201);
        pool.shutdown();
        assertThat(body(doGet(t.adminToken(), "/api/v1/tickets?size=100")).get("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void parallelUpdatesWithSameVersionProduceExactlyOneConflict() throws Exception {
        Tenant t = newTenant();
        String agentA = userToken(t, "agenta", "AGENT");
        String agentB = userToken(t, "agentb", "AGENT");
        JsonNode created = createTicket(agentA, "Contended", "LOW");
        String id = created.get("id").asText();
        long version = created.get("version").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String token : List.of(agentA, agentB)) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return doPut(token, "/api/v1/tickets/" + id, Map.of("title", "Edited by " + token.hashCode(), "description", "d",
                        "priority", "HIGH", "severity", "MAJOR", "tags", List.of(), "version", version)).getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get());
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void searchFilterSortAndPaginationAreBoundedAndTenantScoped() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        for (int i = 0; i < 7; i++) createTicket(agent, "Searchable item " + i, i % 2 == 0 ? "LOW" : "MEDIUM");
        createTicket(agent, "Unrelated", "LOW");

        JsonNode page = body(doGet(agent, "/api/v1/tickets?q=searchable&size=3&page=0&sort=number,asc"));
        assertThat(page.get("totalElements").asInt()).isEqualTo(7);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page.get("content")).hasSize(3);
        assertThat(page.get("content").get(0).get("number").asInt()).isEqualTo(1);

        assertThat(body(doGet(agent, "/api/v1/tickets?priority=MEDIUM")).get("totalElements").asInt()).isEqualTo(3);
        assertThat(body(mvc.perform(get("/api/v1/tickets").param("q", "#7").header("Authorization", "Bearer " + agent)).andReturn())
                .get("totalElements").asInt()).isEqualTo(1); // by number "#7"
        assertThat(body(doGet(agent, "/api/v1/tickets?sort=priority,desc&size=2")).get("content").get(0).get("priority").asText()).isEqualTo("MEDIUM");

        assertThat(doGet(agent, "/api/v1/tickets?size=101").getResponse().getStatus()).isEqualTo(400); // bounded
        assertThat(doGet(agent, "/api/v1/tickets?size=0").getResponse().getStatus()).isEqualTo(400);
        assertThat(doGet(agent, "/api/v1/tickets?sort=password,asc").getResponse().getStatus()).isEqualTo(400); // sort whitelist
        assertThat(doGet(agent, "/api/v1/tickets?status=BOGUS").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void attachmentsAreStoredAsMetadataOnly() throws Exception {
        Tenant t = newTenant();
        Map<String, Object> b = new java.util.HashMap<>(ticketBody("With attachment", "LOW"));
        b.put("attachments", List.of(Map.of("fileName", "log.txt", "contentType", "text/plain", "sizeBytes", 1234)));
        MvcResult r = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(b))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(r).get("attachments").get(0).get("fileName").asText()).isEqualTo("log.txt");
        b.put("attachments", List.of(Map.of("fileName", "huge.bin", "contentType", "x/y", "sizeBytes", 999_999_999_999L)));
        MvcResult bad = mvc.perform(post("/api/v1/tickets").header("Authorization", "Bearer " + t.adminToken())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(b))).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
    }
}
