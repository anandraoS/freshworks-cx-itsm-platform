package com.example.cx.core.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "cx.seed.enabled=true")
class SeedDataTest extends AbstractApiTest {
    static final String DEMO = "Passw0rd!demo";
    @Autowired JdbcTemplate jdbc;

    private long scalar(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    @Test
    void threeTenantsWithContractUsersAndPasswords() throws Exception {
        assertThat(jdbc.queryForList("select slug from organization order by slug", String.class))
                .contains("acme-retail", "globex-it", "initech-health");
        Map<String, List<String>> logins = Map.of(
                "acme-retail", List.of("admin@acme.test", "manager@acme.test", "agent1@acme.test", "agent2@acme.test", "customer1@acme.test"),
                "globex-it", List.of("admin@globex.test", "agent1@globex.test", "customer1@globex.test"),
                "initech-health", List.of("admin@initech.test", "agent1@initech.test"));
        for (var e : logins.entrySet())
            for (String email : e.getValue()) {
                JsonNode r = login(e.getKey(), email, DEMO);
                assertThat(r.at("/user/tenant/slug").asText()).isEqualTo(e.getKey());
                assertThat(r.get("accessToken").asText()).isNotBlank();
            }
        assertThat(login("acme-retail", "manager@acme.test", DEMO).at("/user/roles/0").asText()).isEqualTo("MANAGER");
        // passwords are BCrypt hashes, never the clear text
        assertThat(jdbc.queryForList("select password_hash from app_user where email like '%@acme.test'", String.class))
                .allSatisfy(h -> assertThat(h).startsWith("$2a$").doesNotContain(DEMO));
    }

    @Test
    void syntheticTicketHistoryIsRichAndConsistent() {
        for (var e : Map.of("acme-retail", 150, "globex-it", 100, "initech-health", 60).entrySet()) {
            long tenantTickets = scalar("select count(*) from ticket t join organization o on o.id = t.tenant_id where o.slug = ?", e.getKey());
            assertThat(tenantTickets).isEqualTo((long) e.getValue());
            assertThat(tenantTickets).isBetween(60L, 150L);
            String tid = "(select id from organization where slug = '" + e.getKey() + "')";
            Set<String> statuses = Set.copyOf(jdbc.queryForList("select distinct status from ticket where tenant_id = " + tid, String.class));
            // NEW is not asserted: whether a seeded ticket is still NEW depends on the wall clock (SLA due dates move with "now"), which made this test flaky
            assertThat(statuses).contains("OPEN", "RESOLVED", "CLOSED").hasSizeGreaterThanOrEqualTo(5);
            assertThat(jdbc.queryForList("select distinct priority from ticket where tenant_id = " + tid, String.class)).containsExactlyInAnyOrder("LOW", "MEDIUM", "HIGH", "URGENT");
            assertThat(scalar("select count(*) from ticket_comment where tenant_id = " + tid)).isPositive();
            assertThat(scalar("select count(*) from ticket_comment where visibility = 'INTERNAL' and tenant_id = " + tid)).isPositive();
            assertThat(scalar("select count(*) from ticket_activity where tenant_id = " + tid)).isGreaterThan(tenantTickets);
            // last 45 days, numbered 1..N, counter prepared for N+1
            assertThat(scalar("select count(*) from ticket where tenant_id = " + tid + " and created_at < dateadd('DAY', -46, current_timestamp)")).isZero();
            assertThat(scalar("select max(number) from ticket where tenant_id = " + tid)).isEqualTo(tenantTickets);
            assertThat(scalar("select min(number) from ticket where tenant_id = " + tid)).isEqualTo(1);
            assertThat(scalar("select next_number from ticket_counter where tenant_id = " + tid)).isEqualTo(tenantTickets + 1);
            // SLA data is internally consistent
            assertThat(scalar("select count(*) from ticket where tenant_id = " + tid + " and resolved_at is not null and status not in ('RESOLVED','CLOSED')")).isZero();
            assertThat(scalar("select count(*) from ticket where tenant_id = " + tid + " and resolution_due_at is null")).isZero();
            assertThat(scalar("select count(distinct sla_status) from ticket where tenant_id = " + tid)).isGreaterThanOrEqualTo(3);
        }
        assertThat(scalar("select count(*) from outbox_event")).as("seeding does not flood the outbox").isZero();
    }

    @Test
    void seededDataFeedsTheDashboardAndRespectsTenantBoundaries() throws Exception {
        String manager = login("acme-retail", "manager@acme.test", DEMO).get("accessToken").asText();
        JsonNode d = body(doGet(manager, "/api/v1/analytics/dashboard?days=30"));
        assertThat(d.get("openTickets").asInt()).isPositive();
        assertThat(d.get("ticketsByPriority").size()).isEqualTo(4);
        assertThat(d.get("resolutionTrend").size()).isEqualTo(30);
        assertThat(d.get("avgFirstResponseMinutes").asDouble()).isPositive();
        assertThat(d.get("avgResolutionMinutes").asDouble()).isPositive();
        assertThat(d.get("slaCompliancePercent").asDouble()).isBetween(0.0, 100.0);
        assertThat(d.get("agentWorkload").size()).isPositive();
        assertThat(d.get("tenantActivity").get("ticketsCreatedLast7d").asInt()).isPositive();
        assertThat(d.get("aiAcceptanceRatePercent").asDouble()).isZero(); // AI module not installed yet: documented extension point

        String customer = login("acme-retail", "customer1@acme.test", DEMO).get("accessToken").asText();
        JsonNode own = body(doGet(customer, "/api/v1/tickets?size=100"));
        assertThat(own.get("totalElements").asInt()).isPositive().isLessThan(150);
        assertThat(own.get("content").findValuesAsText("id")).isNotEmpty();
        for (JsonNode t : own.get("content")) assertThat(t.at("/requester/displayName").asText()).isEqualTo("Casey Customer");
        // a Globex manager-level token cannot see Acme tickets
        String globexAdmin = login("globex-it", "admin@globex.test", DEMO).get("accessToken").asText();
        String acmeTicket = body(doGet(manager, "/api/v1/tickets?size=1")).at("/content/0/id").asText();
        assertThat(doGet(globexAdmin, "/api/v1/tickets/" + acmeTicket).getResponse().getStatus()).isEqualTo(404);
        // customer cannot see INTERNAL comments on any of their tickets
        for (JsonNode t : own.get("content")) {
            JsonNode comments = body(doGet(customer, "/api/v1/tickets/" + t.get("id").asText() + "/comments"));
            assertThat(comments.findValuesAsText("visibility")).doesNotContain("INTERNAL");
        }
    }
}
