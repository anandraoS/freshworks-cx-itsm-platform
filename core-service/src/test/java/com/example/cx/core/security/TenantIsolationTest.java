package com.example.cx.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Tenant B must never be able to read, update or list anything of tenant A - through any endpoint. */
class TenantIsolationTest extends AbstractApiTest {

    @Test
    void tenantBCannotTouchTenantATickets() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        String agentA = userToken(a, "agent", "AGENT");
        String agentB = userToken(b, "agent", "AGENT");
        UUID agentAId = userId(agentA);
        JsonNode ticket = createTicket(agentA, "Secret of tenant A", "LOW");
        String id = ticket.get("id").asText();
        doPost(agentA, "/api/v1/tickets/" + id + "/comments", Map.of("body", "internal note A", "visibility", "INTERNAL"));

        // read / update / transition / assign / comments / activity: always 404 (never 403, never data)
        assertThat(doGet(agentB, "/api/v1/tickets/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(doPut(agentB, "/api/v1/tickets/" + id, Map.of("title", "pwned", "description", "d", "priority", "LOW",
                "severity", "MINOR", "tags", List.of(), "version", 0)).getResponse().getStatus()).isEqualTo(404);
        assertThat(doPost(agentB, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "OPEN", "version", 0)).getResponse().getStatus()).isEqualTo(404);
        assertThat(doPost(agentB, "/api/v1/tickets/" + id + "/assign", Map.of("version", 0)).getResponse().getStatus()).isEqualTo(404);
        assertThat(doGet(agentB, "/api/v1/tickets/" + id + "/comments").getResponse().getStatus()).isEqualTo(404);
        assertThat(doPost(agentB, "/api/v1/tickets/" + id + "/comments", Map.of("body", "x", "visibility", "PUBLIC")).getResponse().getStatus()).isEqualTo(404);
        assertThat(doGet(agentB, "/api/v1/tickets/" + id + "/activity").getResponse().getStatus()).isEqualTo(404);

        // list: B sees none of A's; searching A's title finds nothing
        assertThat(body(doGet(agentB, "/api/v1/tickets")).get("totalElements").asInt()).isZero();
        assertThat(body(doGet(agentB, "/api/v1/tickets?q=Secret")).get("totalElements").asInt()).isZero();
        assertThat(body(doGet(agentA, "/api/v1/tickets")).get("totalElements").asInt()).isEqualTo(1);

        // the ticket is untouched
        JsonNode still = body(doGet(agentA, "/api/v1/tickets/" + id));
        assertThat(still.get("title").asText()).isEqualTo("Secret of tenant A");
        assertThat(still.get("version").asLong()).isZero();

        // cross-tenant references inside a request are rejected
        JsonNode bTicket = createTicket(agentB, "B ticket", "LOW");
        MvcResult assign = doPost(agentB, "/api/v1/tickets/" + bTicket.get("id").asText() + "/assign",
                Map.of("assigneeId", agentAId.toString(), "version", 0));
        assertThat(assign.getResponse().getStatus()).isEqualTo(400);
        UUID categoryOfA = UUID.fromString(body(doGet(agentA, "/api/v1/ticket-categories")).get(0).get("id").asText());
        Map<String, Object> withForeignCategory = new java.util.HashMap<>(ticketBody("x", "LOW"));
        withForeignCategory.put("categoryId", categoryOfA.toString());
        MvcResult create = mvc.perform(post(agentB, "/api/v1/tickets", withForeignCategory)).andReturn();
        assertThat(create.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void clientSuppliedTenantIdentifiersAreIgnored() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        createTicket(a.adminToken(), "A only", "LOW");
        MvcResult r = mvc.perform(get("/api/v1/tickets").header("Authorization", "Bearer " + b.adminToken())
                .header("X-Tenant-Id", a.id().toString()).header("X-Tenant", a.slug()).param("tenantId", a.id().toString())).andReturn();
        assertThat(body(r).get("totalElements").asInt()).isZero();
    }

    @Test
    void usersAnalyticsAuditSettingsAndConfigAreTenantScoped() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        String agentA = userToken(a, "agent", "AGENT");
        UUID agentAId = userId(agentA);
        createTicket(agentA, "A1", "LOW");
        createTicket(agentA, "A2", "LOW");

        // users
        JsonNode usersB = body(doGet(b.adminToken(), "/api/v1/users"));
        assertThat(usersB.findValuesAsText("email")).allMatch(e -> e.endsWith("@" + b.slug() + ".test"));
        MvcResult updateForeignUser = doPut(b.adminToken(), "/api/v1/users/" + agentAId,
                Map.of("displayName", "hijacked", "roles", List.of("ADMIN"), "active", true));
        assertThat(updateForeignUser.getResponse().getStatus()).isEqualTo(404);

        // analytics (also proves the cache key is tenant-scoped: A is cached first, B must not see A numbers)
        assertThat(body(doGet(a.adminToken(), "/api/v1/analytics/dashboard")).get("openTickets").asInt()).isEqualTo(2);
        assertThat(body(doGet(a.adminToken(), "/api/v1/analytics/dashboard")).get("openTickets").asInt()).isEqualTo(2);
        JsonNode dashB = body(doGet(b.adminToken(), "/api/v1/analytics/dashboard"));
        assertThat(dashB.get("openTickets").asInt()).isZero();
        assertThat(dashB.get("tenantActivity").get("ticketsCreatedLast7d").asInt()).isZero();

        // audit
        JsonNode auditB = body(doGet(b.adminToken(), "/api/v1/audit-events?size=100"));
        assertThat(auditB.get("content").findValuesAsText("actorEmail")).noneMatch(e -> e.contains(a.slug()));
        assertThat(auditB.get("content").findValuesAsText("entityType")).doesNotContain("TICKET");
        assertThat(body(doGet(b.adminToken(), "/api/v1/audit-events?actorId=" + agentAId)).get("totalElements").asInt()).isZero();
        assertThat(body(doGet(a.adminToken(), "/api/v1/audit-events?entityType=TICKET")).get("totalElements").asInt()).isEqualTo(2);

        // settings, categories, workflows, SLA policies
        assertThat(body(doGet(b.adminToken(), "/api/v1/tenant/settings")).get("name").asText()).isEqualTo("Org " + b.slug());
        String catA = body(doGet(a.adminToken(), "/api/v1/ticket-categories")).get(0).get("id").asText();
        assertThat(doPut(b.adminToken(), "/api/v1/ticket-categories/" + catA,
                Map.of("name", "x", "type", "SUPPORT", "defaultPriority", "LOW")).getResponse().getStatus()).isEqualTo(404);
        MvcResult delCat = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/ticket-categories/" + catA).header("Authorization", "Bearer " + b.adminToken())).andReturn();
        assertThat(delCat.getResponse().getStatus()).isEqualTo(404);
        String wfA = body(doGet(a.adminToken(), "/api/v1/workflows")).get(0).get("id").asText();
        assertThat(doGet(b.adminToken(), "/api/v1/workflows/" + wfA).getResponse().getStatus()).isEqualTo(404);
        assertThat(doPost(b.adminToken(), "/api/v1/workflows/" + wfA + "/activate", Map.of()).getResponse().getStatus()).isEqualTo(404);
        String slaA = body(doGet(a.adminToken(), "/api/v1/sla-policies")).get(0).get("id").asText();
        assertThat(doPut(b.adminToken(), "/api/v1/sla-policies/" + slaA, Map.of("priority", "URGENT", "firstResponseMinutes", 10,
                "resolutionMinutes", 20, "escalationAfterPercent", List.of(50))).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void credentialsOfOneTenantDoNotWorkInAnother() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        MvcResult r = doLoginRaw(b.slug(), a.adminEmail(), PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    private MvcResult doLoginRaw(String slug, String email, String password) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("tenantSlug", slug, "email", email, "password", password)))).andReturn();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String token, String path, Object body) throws Exception {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
}
