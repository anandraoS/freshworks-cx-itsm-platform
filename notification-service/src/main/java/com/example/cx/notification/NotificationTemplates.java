package com.example.cx.notification;

import com.example.cx.events.DomainEvent;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders title/body from the event type and payload. Placeholders: {@code {key}} or {@code {key|fallback}}.
 * Payload values are untrusted: control characters are stripped and values truncated (log / header injection hygiene);
 * clients must still render bodies as plain text.
 */
public final class NotificationTemplates {

    public record Rendered(String category, String title, String body, UUID ticketId) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)(?:\\|([^}]*))?}");
    private static final int MAX_VALUE = 200;

    private record Template(String title, String body) {}

    private static final Map<String, Template> TEMPLATES = Map.of(
            "ticket.created", new Template("New ticket {ticketNumber|}", "Ticket {ticketNumber|} \"{title|Untitled}\" was created (priority {priority|n/a})."),
            "ticket.assigned", new Template("Ticket {ticketNumber|} assigned", "Ticket {ticketNumber|} \"{title|Untitled}\" was assigned to {assigneeName|an agent}."),
            "ticket.status_changed", new Template("Ticket {ticketNumber|} status changed", "Ticket {ticketNumber|} \"{title|Untitled}\" moved from {fromStatus|?} to {toStatus|?}."),
            "ticket.commented", new Template("New comment on ticket {ticketNumber|}", "{actorName|Someone} commented on \"{title|Untitled}\": {commentSnippet|}"),
            "ticket.escalated", new Template("Ticket {ticketNumber|} escalated", "Ticket {ticketNumber|} \"{title|Untitled}\" was escalated to level {escalationLevel|?}."),
            "ticket.sla_breached", new Template("SLA breached: ticket {ticketNumber|}", "The {slaType|SLA} for ticket {ticketNumber|} \"{title|Untitled}\" has been breached."),
            "ai.suggestion_decided", new Template("AI suggestion {decision|decided}", "The AI suggestion for ticket {ticketNumber|} \"{title|Untitled}\" was {decision|decided} by {actorName|an agent}."));

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ticket.status.changed", "ticket.status_changed"),
            Map.entry("ticket.transitioned", "ticket.status_changed"),
            Map.entry("ticket.comment.added", "ticket.commented"),
            Map.entry("ticket.comment_added", "ticket.commented"),
            Map.entry("ticket.sla.breached", "ticket.sla_breached"),
            Map.entry("ai.suggestion.decided", "ai.suggestion_decided"),
            Map.entry("ai.decided", "ai.suggestion_decided"));

    public static String canonicalType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(t, t);
    }

    public boolean supports(String type) { return TEMPLATES.containsKey(canonicalType(type)); }

    public Rendered render(DomainEvent event) {
        String canonical = canonicalType(event.type());
        Map<String, String> payload = event.payload() == null ? Map.of() : event.payload();
        Template t = TEMPLATES.getOrDefault(canonical,
                new Template("Update on ticket {ticketNumber|}", "Event " + sanitize(canonical).replace("{", "(").replace("}", ")")
                        + " occurred on ticket {ticketNumber|} \"{title|Untitled}\"."));
        return new Rendered(canonical, fill(t.title(), payload).strip(), fill(t.body(), payload).strip(), parseUuid(payload.get("ticketId")));
    }

    private static String fill(String template, Map<String, String> payload) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = payload.get(m.group(1));
            String value = (v == null || v.isBlank()) ? (m.group(2) == null ? "" : m.group(2)) : sanitize(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String sanitize(String v) {
        String s = v.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) + "..." : s;
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s.trim()); } catch (IllegalArgumentException e) { return null; }
    }
}
