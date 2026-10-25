package com.example.cx.core.outbox;

import com.example.cx.core.common.CorrelationIdFilter;
import com.example.cx.events.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Docker-profile publisher: topic exchange {@code cx.events}, routing key = event type, PERSISTENT messages, publisher
 * confirms (blocks the relay until the broker acks; a nack/timeout throws so the outbox row is retried), correlation id in a
 * header. NOT executed in the authoring environment (no RabbitMQ / Docker available).
 */
@Component
@Profile("docker")
public class RabbitEventPublisher implements EventPublisher {
    private static final long CONFIRM_TIMEOUT_MS = 5_000;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public RabbitEventPublisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    @Override
    public void publish(DomainEvent event) {
        byte[] body;
        try {
            body = mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event " + event.eventId(), e);
        }
        rabbit.invoke(ops -> {
            ops.send(DomainEvent.EXCHANGE, event.type(),
                    org.springframework.amqp.core.MessageBuilder.withBody(body)
                            .setContentType("application/json")
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                            .setMessageId(event.eventId().toString())
                            .setHeader(CorrelationIdFilter.HEADER, event.correlationId())
                            .setHeader("tenantId", event.tenantId().toString())
                            .build(),
                    new CorrelationData(event.eventId().toString()));
            ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }

    @Configuration
    @Profile("docker")
    static class Topology {
        @Bean
        TopicExchange cxEventsExchange() { return ExchangeBuilder.topicExchange(DomainEvent.EXCHANGE).durable(true).build(); }
    }
}
