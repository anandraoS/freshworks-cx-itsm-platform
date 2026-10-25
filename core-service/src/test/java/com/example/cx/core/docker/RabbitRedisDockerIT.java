package com.example.cx.core.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.cache.RedisCacheService;
import com.example.cx.core.config.CxProperties;
import com.example.cx.core.outbox.RabbitEventPublisher;
import com.example.cx.events.DomainEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DOCKER-ONLY (tagged "docker", excluded by default). NOT EXECUTED in the authoring environment (no Docker available).
 * Verifies against real brokers: RabbitMQ publisher confirms + persistent delivery + headers, and Redis caching with a real
 * Redis outage (container stopped) degrading to the loader.
 */
@Tag("docker")
@Testcontainers
class RabbitRedisDockerIT {
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final TypeReference<String> STR = new TypeReference<>() {};

    @Test
    void eventsArePublishedPersistentWithConfirmsAndCorrelationHeader() throws Exception {
        CachingConnectionFactory cf = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        RabbitTemplate template = new RabbitTemplate(cf);
        RabbitAdmin admin = new RabbitAdmin(cf);
        admin.declareExchange(new org.springframework.amqp.core.TopicExchange(DomainEvent.EXCHANGE, true, false));
        var queue = new org.springframework.amqp.core.Queue("it.events", false, false, true);
        admin.declareQueue(queue);
        admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(queue)
                .to(new org.springframework.amqp.core.TopicExchange(DomainEvent.EXCHANGE)).with("ticket.#"));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RabbitEventPublisher publisher = new RabbitEventPublisher(template, mapper);
        DomainEvent event = new DomainEvent(UUID.randomUUID(), "ticket.sla.breached", 1, UUID.randomUUID(), "corr-it-1", Instant.now(), Map.of("ticketId", "t1"));
        publisher.publish(event);

        Message received = template.receive("it.events", 5000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(received.getMessageProperties().getHeader("X-Correlation-Id").toString()).isEqualTo("corr-it-1");
        assertThat(new String(received.getBody())).contains(event.eventId().toString());
        cf.destroy();
    }

    @Test
    void redisCacheHitsThenDegradesGracefullyWhenRedisStops() {
        LettuceConnectionFactory lcf = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        lcf.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(lcf);
        redis.afterPropertiesSet();
        RedisCacheService cache = new RedisCacheService(redis, new ObjectMapper(), new SimpleMeterRegistry(),
                new CxProperties(null, null, null, null, null, new CxProperties.Cache(1000, 1)));
        UUID tenant = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.getOrLoad(tenant, "k", Duration.ofSeconds(30), STR, () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(cache.getOrLoad(tenant, "k", Duration.ofSeconds(30), STR, () -> "v" + loads.incrementAndGet())).isEqualTo("v1"); // hit
        assertThat(redis.hasKey("t:" + tenant + ":k")).isTrue();
        cache.evictByPrefix(tenant, "k");
        assertThat(redis.hasKey("t:" + tenant + ":k")).isFalse();

        REDIS.stop(); // real outage
        assertThat(cache.getOrLoad(tenant, "k", Duration.ofSeconds(30), STR, () -> "from-db")).isEqualTo("from-db");
        lcf.destroy();
    }
}
