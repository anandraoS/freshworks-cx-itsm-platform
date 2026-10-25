package com.example.cx.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.NotificationTemplates.Rendered;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTemplatesTest {
    private final NotificationTemplates t = new NotificationTemplates();
    private final UUID ticketId = UUID.randomUUID();

    private DomainEvent ev(String type, Map<String, String> extra) {
        Map<String, String> p = new HashMap<>(Map.of("ticketId", ticketId.toString(), "ticketNumber", "T-7", "title", "VPN down"));
        p.putAll(extra);
        return new DomainEvent(UUID.randomUUID(), type, 1, UUID.randomUUID(), "c", Instant.now(), p);
    }

    @Test
    void ticketCreated() {
        Rendered r = t.render(ev("ticket.created", Map.of("priority", "HIGH")));
        assertThat(r.title()).isEqualTo("New ticket T-7");
        assertThat(r.body()).isEqualTo("Ticket T-7 \"VPN down\" was created (priority HIGH).");
        assertThat(r.ticketId()).isEqualTo(ticketId);
        assertThat(r.category()).isEqualTo("ticket.created");
    }

    @Test
    void assignedStatusCommentedEscalatedSlaAndAi() {
        assertThat(t.render(ev("ticket.assigned", Map.of("assigneeName", "Dana"))).body()).contains("assigned to Dana");
        assertThat(t.render(ev("ticket.status.changed", Map.of("fromStatus", "OPEN", "toStatus", "RESOLVED"))).body())
                .contains("moved from OPEN to RESOLVED");
        assertThat(t.render(ev("ticket.commented", Map.of("actorName", "Sam", "commentSnippet", "Try rebooting"))).body())
                .isEqualTo("Sam commented on \"VPN down\": Try rebooting");
        assertThat(t.render(ev("ticket.escalated", Map.of("escalationLevel", "2"))).body()).contains("level 2");
        assertThat(t.render(ev("ticket.sla.breached", Map.of())).title()).isEqualTo("SLA breached: ticket T-7");
        Rendered ai = t.render(ev("ai.suggestion.decided", Map.of("decision", "APPROVED", "actorName", "Dana")));
        assertThat(ai.title()).isEqualTo("AI suggestion APPROVED");
        assertThat(ai.body()).contains("was APPROVED by Dana");
    }

    @Test
    void aliasesMapToSameTemplate() {
        assertThat(NotificationTemplates.canonicalType("TICKET.Status_Changed")).isEqualTo("ticket.status_changed");
        assertThat(NotificationTemplates.canonicalType("ticket.comment.added")).isEqualTo("ticket.commented");
        assertThat(t.supports("ticket.sla_breached")).isTrue();
        assertThat(t.supports("ticket.unknown")).isFalse();
    }

    @Test
    void missingPlaceholdersUseFallbacks() {
        DomainEvent e = new DomainEvent(UUID.randomUUID(), "ticket.assigned", 1, UUID.randomUUID(), null, Instant.now(), null);
        Rendered r = t.render(e);
        assertThat(r.body()).isEqualTo("Ticket  \"Untitled\" was assigned to an agent.");
        assertThat(r.ticketId()).isNull();
    }

    @Test
    void unknownTypeFallsBackToGenericTemplate() {
        Rendered r = t.render(ev("ticket.merged", Map.of()));
        assertThat(r.title()).isEqualTo("Update on ticket T-7");
        assertThat(r.body()).contains("ticket.merged");
    }

    @Test
    void untrustedValuesAreSanitizedAndTruncated() {
        String evil = "line1\r\nFAKE LOG LINE {ticketNumber} $1\u0000" + "x".repeat(500);
        Rendered r = t.render(ev("ticket.commented", Map.of("commentSnippet", evil)));
        assertThat(r.body()).doesNotContain("\n").doesNotContain("\r").doesNotContain("\u0000");
        assertThat(r.body()).contains("{ticketNumber}");          // not re-expanded
        assertThat(r.body().length()).isLessThan(400);
    }

    @Test
    void invalidTicketIdIsIgnored() {
        assertThat(t.render(ev("ticket.created", Map.of("ticketId", "nope"))).ticketId()).isNull();
    }
}
