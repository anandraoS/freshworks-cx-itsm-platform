package com.example.cx.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import com.example.cx.events.DomainEvent;
import com.example.cx.events.EventListener;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Local runtime end to end: create ticket -> outbox row -> relay -> {@code LocalEventPublisher} -> the embedded notification module in-process
 * listener -> {@code notif_notification} rows -> {@code GET /api/v1/notifications} on the core application (port 8081 in a real run).
 * The embedded module is enabled by {@code cx.notifications.embedded=true} in application-local.yml (the test profile is "local,test").
 */
class EmbeddedNotificationsTest extends AbstractApiTest {
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired List<EventListener> listeners;

    @org.junit.jupiter.api.BeforeEach
    void parkLeftoversOfOtherTests() {
        // the Spring context (and its H2 database) is shared with other test classes: park their undelivered events so that only this test events are relayed
        jdbc.update("update outbox_event set status = 'SENT', sent_at = current_timestamp where status in ('PENDING', 'PROCESSING')");
    }

    private JsonNode inbox(String token) throws Exception { return body(doGet(token, "/api/v1/notifications?size=100")); }

    private int unread(String token) throws Exception { return body(doGet(token, "/api/v1/notifications/unread-count")).get("count").asInt(); }

    /** Relays until nothing is due. The context is shared with other tests, so the backlog can be larger than one relay batch (50). */
    private void drain() {
        for (int i = 0; i < 2000; i++) {
            Long due = jdbc.queryForObject("select count(*) from outbox_event where status = 'PENDING' and next_attempt_at <= ?", Long.class, Timestamp.from(Instant.now()));
            if (due == null || due == 0 || relay.relayOnce() == 0) return;
        }
    }

    private EventListener embeddedListener() {
        return listeners.stream().filter(l -> l.getClass().getSimpleName().equals("InProcessEventListener")).findFirst().orElseThrow();
    }

    @Test
    void createTicketReachesAgentInboxesThroughTheRelayTenantScopedAndIdempotent() throws Exception {
        Tenant a = newTenant();
        String agent1 = userToken(a, "agent1", "AGENT");
        String agent2 = userToken(a, "agent2", "AGENT");
        String customer = userToken(a, "cust", "CUSTOMER");
        Tenant b = newTenant();                                   // another tenant with its own agent
        String agentB = userToken(b, "agentb", "AGENT");
        drain();                                                  // leftovers of other tests / registration events
        long before = jdbc.queryForObject("select count(*) from notif_notification", Long.class);

        assertThat(embeddedListener()).isNotNull();
        assertThat(inbox(agent1).get("totalElements").asInt()).isZero();

        JsonNode ticket = createTicket(customer, "Printer on fire", "HIGH");
        // nothing is delivered before the relay runs (outbox pattern: the event is only persisted in the business transaction)
        assertThat(inbox(agent1).get("totalElements").asInt()).isZero();

        drain();

        for (String agent : List.of(agent1, agent2)) {
            JsonNode page = inbox(agent);
            // the default workflow auto-assigns the ticket to one agent, who additionally gets ticket.assigned: 1 or 2 rows, exactly one ticket.created
            JsonNode created = null;
            int createdCount = 0;
            for (JsonNode c : page.get("content")) if (c.get("type").asText().equals("ticket.created")) { created = c; createdCount++; }
            assertThat(createdCount).as(page.toString()).isEqualTo(1);
            JsonNode n = created;
            assertThat(n.get("type").asText()).isEqualTo("ticket.created");
            assertThat(n.get("title").asText()).contains(String.valueOf(ticket.get("number").asLong()));
            assertThat(n.get("body").asText()).contains("Printer on fire");
            assertThat(n.get("ticketId").asText()).isEqualTo(ticket.get("id").asText());
            assertThat(n.get("read").asBoolean()).isFalse();
            assertThat(unread(agent)).isEqualTo(page.get("totalElements").asInt());
        }
        assertThat(inbox(customer).get("totalElements").asInt()).isZero();   // the actor is never notified of their own action
        assertThat(inbox(agentB).get("totalElements").asInt()).isZero();     // tenant isolation: the other tenant sees nothing
        assertThat(jdbc.queryForObject("select count(*) from notif_notification", Long.class)).isEqualTo(before + 3);   // 2 x ticket.created + 1 x ticket.assigned (auto-assignee)
        // every row of tenant A belongs to a user of tenant A
        assertThat(jdbc.queryForObject("select count(*) from notif_notification n where n.tenant_id = ? and not exists "
                + "(select 1 from app_user u where u.id = n.user_id and u.tenant_id = n.tenant_id)", Long.class, a.id())).isZero();

        // a status change reaches the requester (customer), not the actor
        String ticketId = ticket.get("id").asText();
        JsonNode open = body(doPost(agent1, "/api/v1/tickets/" + ticketId + "/transition", Map.of("toStatus", "OPEN", "version", 0)));
        assertThat(open.get("status").asText()).isEqualTo("OPEN");
        drain();
        JsonNode custInbox = inbox(customer);
        assertThat(custInbox.get("totalElements").asInt()).isEqualTo(1);
        assertThat(custInbox.get("content").get(0).get("type").asText()).isEqualTo("ticket.status_changed");

        // mark read through the contract endpoint
        String nid = custInbox.get("content").get(0).get("id").asText();
        assertThat(doPost(agentB, "/api/v1/notifications/" + nid + "/read", Map.of()).getResponse().getStatus()).isEqualTo(404);
        assertThat(doPost(customer, "/api/v1/notifications/" + nid + "/read", Map.of()).getResponse().getStatus()).isEqualTo(204);
        assertThat(unread(customer)).isZero();
    }

    @Test
    void duplicateDeliveryOfTheSameEventCreatesNoSecondNotification() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent1", "AGENT");
        String customer = userToken(t, "cust", "CUSTOMER");
        drain();
        createTicket(customer, "Duplicate me", "LOW");
        drain();
        assertThat(inbox(agent).get("totalElements").asInt()).isEqualTo(1);

        // (1) the relay re-delivers the same outbox row (at-least-once: e.g. crash after publish, before marking SENT)
        UUID eventId = jdbc.queryForObject("select id from outbox_event where tenant_id = ? and event_type = 'ticket.created'", UUID.class, t.id());
        jdbc.update("update outbox_event set status = 'PENDING', sent_at = null, next_attempt_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), eventId);
        assertThat(relay.relayOnce()).isGreaterThanOrEqualTo(1);

        // (2) the listener is invoked directly with the very same event again
        String payload = jdbc.queryForObject("select payload from outbox_event where id = ?", String.class, eventId);
        DomainEvent event = json.readValue(payload, DomainEvent.class);
        embeddedListener().onEvent(event);
        embeddedListener().onEvent(event);

        assertThat(inbox(agent).get("totalElements").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notif_notification where event_id = ?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from notif_processed_event where event_id = ? and status = 'DONE'", Long.class, eventId)).isEqualTo(1L);
    }

    @Test
    void forgedRecipientOfAnotherTenantIsNotNotified() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        String agentA = userToken(a, "agent1", "AGENT");
        String agentB = userToken(b, "agent1", "AGENT");
        UUID userA = userId(agentA), userB = userId(agentB);
        DomainEvent forged = new DomainEvent(UUID.randomUUID(), "ticket.created", 1, a.id(), "corr", Instant.now(),
                Map.of("ticketId", UUID.randomUUID().toString(), "ticketNumber", "7", "title", "Forged",
                        "recipientUserIds", userA + "," + userB));
        embeddedListener().onEvent(forged);
        assertThat(inbox(agentA).get("totalElements").asInt()).isEqualTo(1);
        assertThat(inbox(agentB).get("totalElements").asInt()).isZero();
    }

    @Test
    void contractEndpointsRequireAValidTokenAndRevokedTokensAreRejected() throws Exception {
        Tenant t = newTenant();
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/notifications")).andReturn().getResponse().getStatus()).isEqualTo(401);
        String agent = userToken(t, "agent1", "AGENT");
        assertThat(doGet(agent, "/api/v1/notifications").getResponse().getStatus()).isEqualTo(200);
        assertThat(doPost(agent, "/api/v1/auth/logout", Map.of()).getResponse().getStatus()).isEqualTo(204);
        assertThat(doGet(agent, "/api/v1/notifications").getResponse().getStatus()).isEqualTo(401);
    }
}
