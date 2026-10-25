package com.example.cx.notification.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.NotificationServiceApplication;
import com.example.cx.notification.store.NotificationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-broker integration test. Requires Docker (Testcontainers): tagged "docker", excluded by default
 * (surefire excludedGroups=docker). Run with: mvn -pl notification-service verify -DexcludedGroups=none -Dgroups=docker
 */
@Tag("docker")
@Testcontainers
@SpringBootTest(classes = NotificationServiceApplication.class)
class NotificationRabbitDockerIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", () -> "guest");     // Testcontainers default dummy credentials
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("cx.notifications.rabbit.enabled", () -> "true");
        r.add("cx.notifications.retry-delay-ms", () -> "300");
        r.add("cx.notifications.max-attempts", () -> "2");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired NotificationStore store;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private Message json(Object o) throws Exception {
        return MessageBuilder.withBody(mapper.writeValueAsBytes(o)).setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
    }

    @Test
    void eventPublishedToExchangeIsConsumedOnceEvenWhenPublishedTwice() throws Exception {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        DomainEvent e = new DomainEvent(UUID.randomUUID(), "ticket.created", 1, tenant, "c", Instant.now(),
                Map.of("recipientUserIds", user.toString(), "ticketNumber", "T-1", "title", "x"));
        rabbit.send(DomainEvent.EXCHANGE, "ticket.created", json(e));
        rabbit.send(DomainEvent.EXCHANGE, "ticket.created", json(e));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(store.count(tenant, user, false)).isEqualTo(1));
    }

    @Test
    void poisonMessageEndsInDeadLetterQueue() throws Exception {
        rabbit.send(DomainEvent.EXCHANGE, "ticket.created",
                MessageBuilder.withBody("{broken".getBytes()).setContentType("application/json").build());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(rabbit.receive(DomainEvent.NOTIFICATION_DLQ)).isNotNull());
    }
}
