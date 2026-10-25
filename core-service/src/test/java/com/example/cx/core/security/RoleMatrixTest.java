package com.example.cx.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoleMatrixTest extends AbstractApiTest {

    private int status(String token, String path) throws Exception {
        return doGet(token, path).getResponse().getStatus();
    }

    @Test
    void endpointAccessByRole() throws Exception {
        Tenant t = newTenant();
        String customer = userToken(t, "cust", "CUSTOMER");
        String agent = userToken(t, "agent", "AGENT");
        String manager = userToken(t, "mgr", "MANAGER");
        String admin = t.adminToken();

        // path -> expected status for [customer, agent, manager, admin]
        Map<String, int[]> matrix = new java.util.LinkedHashMap<>();
        matrix.put("/api/v1/tickets", new int[]{200, 200, 200, 200});
        matrix.put("/api/v1/ticket-categories", new int[]{200, 200, 200, 200});
        matrix.put("/api/v1/users", new int[]{403, 200, 200, 200});
        matrix.put("/api/v1/analytics/dashboard", new int[]{403, 403, 200, 200});
        matrix.put("/api/v1/workflows", new int[]{403, 403, 200, 200});
        matrix.put("/api/v1/sla-policies", new int[]{403, 403, 200, 200});
        matrix.put("/api/v1/tenant/settings", new int[]{403, 403, 403, 200});
        matrix.put("/api/v1/audit-events", new int[]{403, 403, 403, 200});
        String[] tokens = {customer, agent, manager, admin};
        String[] names = {"customer", "agent", "manager", "admin"};
        for (var e : matrix.entrySet())
            for (int i = 0; i < tokens.length; i++)
                assertThat(status(tokens[i], e.getKey())).as("%s as %s", e.getKey(), names[i]).isEqualTo(e.getValue()[i]);

        // write endpoints
        Map<String, Object> wf = Map.of("name", "wf", "rules", List.of(), "transitions", List.of());
        assertThat(doPost(customer, "/api/v1/workflows", wf).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(agent, "/api/v1/workflows", wf).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(manager, "/api/v1/workflows", wf).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(admin, "/api/v1/workflows", wf).getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> cat = Map.of("name", "NewCat", "type", "SUPPORT", "defaultPriority", "LOW");
        assertThat(doPost(agent, "/api/v1/ticket-categories", cat).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(admin, "/api/v1/ticket-categories", cat).getResponse().getStatus()).isEqualTo(201);
        assertThat(doPost(manager, "/api/v1/users", Map.of("email", "x@y.test", "displayName", "xx", "roles", List.of("AGENT"),
                "password", PASSWORD)).getResponse().getStatus()).isEqualTo(403);
        String slaId = body(doGet(admin, "/api/v1/sla-policies")).get(0).get("id").asText();
        String slaPriority = body(doGet(admin, "/api/v1/sla-policies")).get(0).get("priority").asText();
        Map<String, Object> sla = Map.of("priority", slaPriority, "firstResponseMinutes", 20, "resolutionMinutes", 100, "escalationAfterPercent", List.of(50, 90));
        assertThat(doPut(manager, "/api/v1/sla-policies/" + slaId, sla).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPut(admin, "/api/v1/sla-policies/" + slaId, sla).getResponse().getStatus()).isEqualTo(200);

        // unauthenticated and garbage tokens
        assertThat(mvc.perform(get("/api/v1/users")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(status("not-a-jwt", "/api/v1/users")).isEqualTo(401);
    }

    @Test
    void customersSeeOnlyTheirOwnTicketsAndPublicComments() throws Exception {
        Tenant t = newTenant();
        String c1 = userToken(t, "c1", "CUSTOMER");
        String c2 = userToken(t, "c2", "CUSTOMER");
        String agent = userToken(t, "agent", "AGENT");
        JsonNode t1 = createTicket(c1, "Customer one ticket", "LOW");
        JsonNode t2 = createTicket(c2, "Customer two ticket", "LOW");
        String id1 = t1.get("id").asText();

        assertThat(body(doGet(c1, "/api/v1/tickets")).get("totalElements").asInt()).isEqualTo(1);
        assertThat(body(doGet(c1, "/api/v1/tickets?mine=false&assigneeId=" + java.util.UUID.randomUUID())).get("totalElements").asInt()).isZero();
        assertThat(body(doGet(agent, "/api/v1/tickets")).get("totalElements").asInt()).isEqualTo(2);
        assertThat(doGet(c1, "/api/v1/tickets/" + t2.get("id").asText()).getResponse().getStatus()).isEqualTo(404);
        assertThat(doGet(c2, "/api/v1/tickets/" + id1).getResponse().getStatus()).isEqualTo(404);
        assertThat(doGet(c2, "/api/v1/tickets/" + id1 + "/comments").getResponse().getStatus()).isEqualTo(404);

        // customers cannot edit / assign
        assertThat(doPut(c1, "/api/v1/tickets/" + id1, Map.of("title", "t", "description", "d", "priority", "LOW", "severity", "MINOR",
                "tags", List.of(), "version", 0)).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(c1, "/api/v1/tickets/" + id1 + "/assign", Map.of("version", 0)).getResponse().getStatus()).isEqualTo(403);

        // INTERNAL comments: staff may post, the customer neither sees nor posts them
        assertThat(doPost(agent, "/api/v1/tickets/" + id1 + "/comments", Map.of("body", "staff only note", "visibility", "INTERNAL")).getResponse().getStatus()).isEqualTo(201);
        assertThat(doPost(agent, "/api/v1/tickets/" + id1 + "/comments", Map.of("body", "hello customer", "visibility", "PUBLIC")).getResponse().getStatus()).isEqualTo(201);
        assertThat(doPost(c1, "/api/v1/tickets/" + id1 + "/comments", Map.of("body", "sneaky", "visibility", "INTERNAL")).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(c1, "/api/v1/tickets/" + id1 + "/comments", Map.of("body", "thanks", "visibility", "PUBLIC")).getResponse().getStatus()).isEqualTo(201);
        JsonNode forCustomer = body(doGet(c1, "/api/v1/tickets/" + id1 + "/comments"));
        assertThat(forCustomer.findValuesAsText("visibility")).containsOnly("PUBLIC");
        assertThat(forCustomer.findValuesAsText("body")).doesNotContain("staff only note");
        assertThat(body(doGet(agent, "/api/v1/tickets/" + id1 + "/comments")).findValuesAsText("visibility")).contains("INTERNAL", "PUBLIC");
        // customers do not see staff-only activity (e.g. comment details)
        assertThat(body(doGet(c1, "/api/v1/tickets/" + id1 + "/activity")).findValuesAsText("type")).doesNotContain("COMMENTED", "UPDATED");

        // staff first public reply marks the first response
        assertThat(body(doGet(agent, "/api/v1/tickets/" + id1)).get("firstRespondedAt").isNull()).isFalse();
    }

    @Test
    void customerMayOnlyReopen() throws Exception {
        Tenant t = newTenant();
        String c1 = userToken(t, "c1", "CUSTOMER");
        String agent = userToken(t, "agent", "AGENT");
        JsonNode created = createTicket(c1, "To reopen", "LOW");
        String id = created.get("id").asText();
        assertThat(doPost(c1, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "OPEN", "version", 0)).getResponse().getStatus()).isEqualTo(403);
        long v = 0;
        for (String s : List.of("OPEN", "RESOLVED")) {
            JsonNode n = body(doPost(agent, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", s, "version", v)));
            v = n.get("version").asLong();
        }
        var reopened = doPost(c1, "/api/v1/tickets/" + id + "/transition", Map.of("toStatus", "REOPENED", "version", v));
        assertThat(reopened.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(reopened).get("reopenCount").asInt()).isEqualTo(1);
    }

    @Test
    void lastAdminCannotBeRemoved() throws Exception {
        Tenant t = newTenant();
        String adminId = body(doGet(t.adminToken(), "/api/v1/auth/me")).get("id").asText();
        var r = doPut(t.adminToken(), "/api/v1/users/" + adminId, Map.of("displayName", "Admin", "roles", List.of("AGENT"), "active", true));
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }
}
