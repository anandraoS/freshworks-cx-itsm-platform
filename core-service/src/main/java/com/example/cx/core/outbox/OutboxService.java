package com.example.cx.core.outbox;

import com.example.cx.core.common.CorrelationIdFilter;
import com.example.cx.core.tenancy.TenantContext;
import com.example.cx.events.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * EXTENSION POINT: the ONLY way modules emit domain events. {@code publish} requires an already-active transaction
 * (Propagation.MANDATORY), which makes it impossible to write an event outside the business transaction: either the business
 * change and the event commit together, or neither does. Event types in use: ticket.created, ticket.assigned,
 * ticket.status.changed, ticket.commented, ticket.escalated, ticket.sla.breached, ai.suggestion.decided.
 */
@Service
public class OutboxService {
    public static final int SCHEMA_VERSION = 1;
    private final OutboxRepository repo;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public OutboxService(OutboxRepository repo, ObjectMapper mapper, MeterRegistry meters) {
        this.repo = repo;
        this.mapper = mapper;
        this.meters = meters;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String type, Map<String, String> payload) {
        UUID tenant = TenantContext.require();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, String> safe = new LinkedHashMap<>();
        payload.forEach((k, v) -> { if (v != null) safe.put(k, v); });
        DomainEvent event = new DomainEvent(id, type, SCHEMA_VERSION, tenant, MDC.get(CorrelationIdFilter.MDC_KEY), now, safe);
        OutboxEvent row = new OutboxEvent();
        row.setId(id);
        row.setTenantId(tenant);
        row.setEventType(type);
        row.setPayload(toJson(event));
        row.setStatus(OutboxEvent.PENDING);
        row.setNextAttemptAt(now);
        row.setCreatedAt(now);
        repo.save(row);
        meters.counter("cx.outbox.enqueued", "type", type).increment();
        return id;
    }

    String toJson(DomainEvent e) {
        try {
            return mapper.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise event", ex);
        }
    }

    DomainEvent fromJson(String json) throws JsonProcessingException {
        return mapper.readValue(json, DomainEvent.class);
    }
}
