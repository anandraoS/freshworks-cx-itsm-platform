package com.example.cx.core.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class SlaJobTest extends AbstractApiTest {
    @Autowired SlaMonitorJob job;
    @Autowired SlaService sla;
    @Autowired JdbcTemplate jdbc;

    private long events(UUID tenant, String type) {
        return jdbc.queryForObject("select count(*) from outbox_event where tenant_id = ? and event_type = ?", Long.class, tenant, type);
    }

    private void makeOverdue(String ticketId, int createdHoursAgo, int dueHoursAgo) {
        Instant now = Instant.now();
        jdbc.update("update ticket set created_at = ?, first_response_due_at = ?, resolution_due_at = ?, sla_checked_at = null where id = ?",
                java.sql.Timestamp.from(now.minus(createdHoursAgo, ChronoUnit.HOURS)), java.sql.Timestamp.from(now.minus(createdHoursAgo - 1, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(now.minus(dueHoursAgo, ChronoUnit.HOURS)), UUID.fromString(ticketId));
    }

    @Test
    void breachAndEscalationAreFlaggedOnceAndPublishedViaOutbox() throws Exception {
        Tenant t = newTenant();
        JsonNode ticket = createTicket(t.adminToken(), "Overdue ticket", "MEDIUM");
        String id = ticket.get("id").asText();
        makeOverdue(id, 30, 2);

        job.runOnce();
        JsonNode after = body(doGet(t.adminToken(), "/api/v1/tickets/" + id));
        assertThat(after.get("slaStatus").asText()).isEqualTo("BREACHED");
        assertThat(after.get("escalationLevel").asInt()).isEqualTo(2); // 75% and 100% thresholds crossed
        assertThat(after.get("version").asLong()).isEqualTo(ticket.get("version").asLong()); // job never bumps the optimistic version
        assertThat(events(t.id(), "ticket.sla.breached")).isEqualTo(1);
        assertThat(events(t.id(), "ticket.escalated")).isEqualTo(1);
        assertThat(body(doGet(t.adminToken(), "/api/v1/tickets/" + id + "/activity")).findValuesAsText("type")).contains("SLA_BREACHED", "ESCALATED");

        // idempotent: re-running (or an overlapping node) publishes nothing new
        job.runOnce();
        job.runOnce();
        assertThat(events(t.id(), "ticket.sla.breached")).isEqualTo(1);
        assertThat(events(t.id(), "ticket.escalated")).isEqualTo(1);

        // a user edit with the original version still succeeds (no spurious 409 caused by the job)
        MvcResult upd = doPut(t.adminToken(), "/api/v1/tickets/" + id, Map.of("title", "Overdue ticket!", "description", "d", "priority", "MEDIUM",
                "severity", "MINOR", "tags", java.util.List.of(), "version", ticket.get("version").asLong()));
        assertThat(upd.getResponse().getStatus()).isEqualTo(200);
        // ... and the SLA columns written by the job were not overwritten by the edit (@DynamicUpdate)
        assertThat(body(upd).get("escalationLevel").asInt()).isEqualTo(2);
    }

    @Test
    void pausedAndResolvedTicketsDoNotBreach() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        JsonNode paused = createTicket(agent, "Paused", "MEDIUM");
        String pid = paused.get("id").asText();
        JsonNode open = body(doPost(agent, "/api/v1/tickets/" + pid + "/transition", Map.of("toStatus", "OPEN", "version", 0)));
        doPost(agent, "/api/v1/tickets/" + pid + "/transition", Map.of("toStatus", "PENDING", "version", open.get("version").asLong()));
        makeOverdue(pid, 30, 2);
        JsonNode done = createTicket(agent, "Done", "MEDIUM");
        String did = done.get("id").asText();
        JsonNode o2 = body(doPost(agent, "/api/v1/tickets/" + did + "/transition", Map.of("toStatus", "OPEN", "version", 0)));
        doPost(agent, "/api/v1/tickets/" + did + "/transition", Map.of("toStatus", "RESOLVED", "version", o2.get("version").asLong()));
        makeOverdue(did, 30, 2);

        job.runOnce();
        assertThat(body(doGet(agent, "/api/v1/tickets/" + pid)).get("slaStatus").asText()).isEqualTo("PAUSED");
        assertThat(events(t.id(), "ticket.sla.breached")).isZero();
    }

    @Test
    void scanIsBoundedPerRunAndRotatesFairly() throws Exception {
        Tenant t = newTenant();
        for (int i = 0; i < 3; i++) {
            String id = createTicket(t.adminToken(), "Overdue " + i, "MEDIUM").get("id").asText();
            makeOverdue(id, 30, 2);
        }
        int first = com.example.cx.core.tenancy.TenantContext.callAs(t.id(), () -> sla.scanCurrentTenant(1));
        assertThat(first).isEqualTo(1); // only one ticket examined in this run
        assertThat(events(t.id(), "ticket.sla.breached")).isEqualTo(1);
        com.example.cx.core.tenancy.TenantContext.callAs(t.id(), () -> sla.scanCurrentTenant(1));
        com.example.cx.core.tenancy.TenantContext.callAs(t.id(), () -> sla.scanCurrentTenant(1));
        assertThat(events(t.id(), "ticket.sla.breached")).isEqualTo(3); // never-checked tickets go first, so all three get covered
        com.example.cx.core.tenancy.TenantContext.callAs(t.id(), () -> sla.scanCurrentTenant(10));
        assertThat(events(t.id(), "ticket.sla.breached")).isEqualTo(3);
    }

    @Test
    void jobIteratesAllTenants() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        makeOverdue(createTicket(a.adminToken(), "A overdue", "LOW").get("id").asText(), 200, 10);
        makeOverdue(createTicket(b.adminToken(), "B overdue", "LOW").get("id").asText(), 200, 10);
        job.runOnce();
        assertThat(events(a.id(), "ticket.sla.breached")).isEqualTo(1);
        assertThat(events(b.id(), "ticket.sla.breached")).isEqualTo(1);
    }

    @Test
    void updatingAPolicyChangesNewTicketsOnly() throws Exception {
        Tenant t = newTenant();
        JsonNode urgent = null;
        for (JsonNode p : body(doGet(t.adminToken(), "/api/v1/sla-policies"))) if (p.get("priority").asText().equals("URGENT")) urgent = p;
        assertThat(urgent.get("escalationAfterPercent")).hasSize(3);
        JsonNode before = createTicket(t.adminToken(), "Before policy change", "URGENT");
        MvcResult bad = doPut(t.adminToken(), "/api/v1/sla-policies/" + urgent.get("id").asText(), Map.of("priority", "URGENT",
                "firstResponseMinutes", 100, "resolutionMinutes", 50, "escalationAfterPercent", java.util.List.of(50)));
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        MvcResult ok = doPut(t.adminToken(), "/api/v1/sla-policies/" + urgent.get("id").asText(), Map.of("priority", "URGENT",
                "firstResponseMinutes", 5, "resolutionMinutes", 10, "escalationAfterPercent", java.util.List.of(50, 100)));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode fresh = body(doGet(t.adminToken(), "/api/v1/sla-policies"));
        assertThat(fresh.toString()).contains("\"firstResponseMinutes\":5"); // cache was invalidated by the write
        JsonNode after = createTicket(t.adminToken(), "After policy change", "URGENT");
        assertThat(Instant.parse(after.get("resolutionDueAt").asText())).isBefore(Instant.parse(before.get("resolutionDueAt").asText()));
    }
}
