package com.example.cx.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import com.example.cx.core.cache.CacheService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class WorkflowApiTest extends AbstractApiTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheService cache;
    @Autowired MeterRegistry meters;

    static Map<String, Object> workflow(String name, List<?> rules, List<?> transitions) {
        return Map.of("name", name, "rules", rules, "transitions", transitions);
    }

    static Map<String, Object> rule(String name, List<?> when, List<?> then) { return Map.of("name", name, "when", when, "then", then); }

    @Test
    void validateEndpointReportsErrorsWithoutPersisting() throws Exception {
        Tenant t = newTenant();
        Map<String, Object> bad = workflow("bad", List.of(rule("r", List.of(Map.of("field", "password", "op", "EQ", "value", "x")),
                List.of(Map.of("action", "RUN_SCRIPT", "params", Map.of())))), List.of());
        JsonNode res = body(doPost(t.adminToken(), "/api/v1/workflows/validate", bad));
        assertThat(res.get("valid").asBoolean()).isFalse();
        assertThat(res.get("errors")).hasSize(2);
        Map<String, Object> good = workflow("good", List.of(rule("r", List.of(Map.of("field", "priority", "op", "EQ", "value", "LOW")),
                List.of(Map.of("action", "ADD_TAG", "params", Map.of("tag", "low"))))), List.of());
        assertThat(body(doPost(t.adminToken(), "/api/v1/workflows/validate", good)).get("valid").asBoolean()).isTrue();
        assertThat(body(doGet(t.adminToken(), "/api/v1/workflows")).size()).isEqualTo(1); // only the default v1
    }

    @Test
    void invalidWorkflowIsRejectedOnCreate() throws Exception {
        Tenant t = newTenant();
        Map<String, Object> bad = workflow("bad", List.of(rule("r", List.of(), List.of(Map.of("action", "NOTIFY", "params", Map.of("message", "${7*7}"))))), List.of());
        MvcResult r = doPost(t.adminToken(), "/api/v1/workflows", bad);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body(r).get("fieldErrors").get(0).get("message").asText()).contains("expression syntax");
    }

    @Test
    void versionsAreCreatedAndActivatedExclusively() throws Exception {
        Tenant t = newTenant();
        JsonNode v1 = body(doGet(t.adminToken(), "/api/v1/workflows")).get(0);
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        assertThat(v1.get("active").asBoolean()).isTrue();
        assertThat(v1.get("rules")).hasSize(3);

        MvcResult created = doPost(t.adminToken(), "/api/v1/workflows", workflow("v2", List.of(), List.of()));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode v2 = body(created);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("active").asBoolean()).isFalse(); // creating never activates

        JsonNode activated = body(doPost(t.adminToken(), "/api/v1/workflows/" + v2.get("id").asText() + "/activate", Map.of()));
        assertThat(activated.get("active").asBoolean()).isTrue();
        JsonNode list = body(doGet(t.adminToken(), "/api/v1/workflows"));
        assertThat(list.findValues("active").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);
        assertThat(list.get(0).get("version").asInt()).isEqualTo(2); // newest first
        // the activation is audited
        assertThat(body(doGet(t.adminToken(), "/api/v1/audit-events?entityType=WORKFLOW")).get("content").findValuesAsText("action"))
                .contains("WORKFLOW_ACTIVATED", "WORKFLOW_CREATED");
    }

    @Test
    void activeRulesAreAppliedToNewTickets() throws Exception {
        Tenant t = newTenant();
        String customer = userToken(t, "cust", "CUSTOMER");
        Map<String, Object> wf = workflow("tagger", List.of(
                rule("tag low", List.of(Map.of("field", "priority", "op", "EQ", "value", "LOW")), List.of(Map.of("action", "ADD_TAG", "params", Map.of("tag", "auto-low")))),
                rule("bump printers", List.of(Map.of("field", "title", "op", "CONTAINS", "value", "printer")), List.of(Map.of("action", "SET_PRIORITY", "params", Map.of("priority", "URGENT")),
                        Map.of("action", "ESCALATE", "params", Map.of())))), List.of());
        String id = body(doPost(t.adminToken(), "/api/v1/workflows", wf)).get("id").asText();
        doPost(t.adminToken(), "/api/v1/workflows/" + id + "/activate", Map.of());

        JsonNode plain = createTicket(customer, "Something plain", "LOW");
        assertThat(plain.get("tags").findValuesAsText("")).isEmpty();
        assertThat(plain.get("tags").toString()).contains("auto-low");
        JsonNode printer = createTicket(customer, "Printer is jammed", "MEDIUM");
        assertThat(printer.get("priority").asText()).isEqualTo("URGENT");
        assertThat(printer.get("escalationLevel").asInt()).isEqualTo(1);
        JsonNode activity = body(doGet(t.adminToken(), "/api/v1/tickets/" + printer.get("id").asText() + "/activity"));
        assertThat(activity.findValuesAsText("type")).contains("WORKFLOW_ACTION");
    }

    @Test
    void defaultWorkflowRoutesHighPriorityToTheLeastLoadedAgent() throws Exception {
        Tenant t = newTenant();
        String agentA = userToken(t, "agenta", "AGENT");
        String agentB = userToken(t, "agentb", "AGENT");
        String customer = userToken(t, "cust", "CUSTOMER");
        String idA = userId(agentA).toString(), idB = userId(agentB).toString();
        java.util.Map<String, Integer> load = new java.util.HashMap<>();
        for (int i = 0; i < 4; i++) {
            JsonNode tk = createTicket(customer, "High prio " + i, "HIGH");
            assertThat(tk.get("assignee").isNull()).isFalse();
            load.merge(tk.at("/assignee/id").asText(), 1, Integer::sum);
        }
        // strictly alternating by least load: 2 each (ties are broken deterministically by id)
        assertThat(load).containsOnlyKeys(idA, idB);
        assertThat(load.get(idA)).isEqualTo(2);
        assertThat(load.get(idB)).isEqualTo(2);
        // low priority is not auto-assigned
        assertThat(createTicket(customer, "Low prio", "LOW").get("assignee").isNull()).isTrue();
    }

    @Test
    void customTransitionsReplaceTheDefaultTable() throws Exception {
        Tenant t = newTenant();
        Map<String, Object> wf = workflow("strict", List.of(), List.of(Map.of("from", "NEW", "to", "OPEN"),
                Map.of("from", "OPEN", "to", "RESOLVED"), Map.of("from", "RESOLVED", "to", "CLOSED")));
        String id = body(doPost(t.adminToken(), "/api/v1/workflows", wf)).get("id").asText();
        doPost(t.adminToken(), "/api/v1/workflows/" + id + "/activate", Map.of());
        JsonNode ticket = createTicket(t.adminToken(), "Strict flow", "LOW");
        String tid = ticket.get("id").asText();
        JsonNode open = body(doPost(t.adminToken(), "/api/v1/tickets/" + tid + "/transition", Map.of("toStatus", "OPEN", "version", 0)));
        MvcResult pending = doPost(t.adminToken(), "/api/v1/tickets/" + tid + "/transition", Map.of("toStatus", "PENDING", "version", open.get("version").asLong()));
        assertThat(pending.getResponse().getStatus()).isEqualTo(422); // allowed by default, removed by this workflow
        assertThat(doPost(t.adminToken(), "/api/v1/tickets/" + tid + "/transition",
                Map.of("toStatus", "RESOLVED", "version", open.get("version").asLong())).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void corruptStoredWorkflowFallsBackToTheBuiltInDefault() throws Exception {
        Tenant t = newTenant();
        jdbc.update("update workflow set definition = ? where tenant_id = ? and active = true", "{this is not json", t.id());
        cache.evict(t.id(), WorkflowService.CACHE_KEY);
        double before = meters.counter("cx.workflow.fallback", "reason", "invalid_definition").count();

        // tickets still work and the default state machine is in force
        JsonNode ticket = createTicket(t.adminToken(), "Works despite broken workflow", "HIGH");
        assertThat(ticket.get("assignee").isNull()).isTrue(); // built-in default has no routing rules
        MvcResult open = doPost(t.adminToken(), "/api/v1/tickets/" + ticket.get("id").asText() + "/transition", Map.of("toStatus", "OPEN", "version", 0));
        assertThat(open.getResponse().getStatus()).isEqualTo(200);
        assertThat(meters.counter("cx.workflow.fallback", "reason", "invalid_definition").count()).isGreaterThan(before);
        // listing still renders (broken versions degrade to empty rules instead of failing)
        assertThat(doGet(t.adminToken(), "/api/v1/workflows").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void tenantWithoutActiveWorkflowUsesDefault() throws Exception {
        Tenant t = newTenant();
        jdbc.update("update workflow set active = false where tenant_id = ?", t.id());
        cache.evict(t.id(), WorkflowService.CACHE_KEY);
        JsonNode ticket = createTicket(t.adminToken(), "No active workflow", "LOW");
        assertThat(ticket.get("status").asText()).isEqualTo("NEW");
    }
}
