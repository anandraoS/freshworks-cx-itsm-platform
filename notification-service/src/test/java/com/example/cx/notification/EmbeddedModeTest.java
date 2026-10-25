package com.example.cx.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.events.DomainEvent;
import com.example.cx.events.EventListener;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class EmbeddedModeTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, NotificationAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:emb" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
                        "cx.security.jwt.secret=test-only-dummy-secret-0123456789abcdefXYZ");
    }

    @Test
    void notRegisteredUnlessEmbeddedPropertyIsTrue() {
        runner().run(ctx -> assertThat(ctx).doesNotHaveBean(EventListener.class).doesNotHaveBean(NotificationProcessor.class));
    }

    @Test
    void autoRegistersListenerMigratesOwnTablesAndProcessesEvents() {
        runner().withPropertyValues("cx.notifications.embedded=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(EventListener.class);
            assertThat(ctx).doesNotHaveBean("cxNotificationListenerContainer");   // no RabbitMQ in local mode
            JdbcTemplate jdbc = new JdbcTemplate(ctx.getBean(javax.sql.DataSource.class));
            assertThat(jdbc.queryForObject("select count(*) from notif_schema_history", Integer.class)).isGreaterThan(0);

            UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
            DomainEvent e = TestSupport.event(tenant, "ticket.created", user);
            EventListener listener = ctx.getBean(EventListener.class);
            listener.onEvent(e);
            listener.onEvent(e);   // at-least-once dispatcher delivers twice
            assertThat(jdbc.queryForObject("select count(*) from notif_notification", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void inProcessListenerRetriesThenDeadLettersWithoutThrowing() {
        TestSupport.Fixture f = TestSupport.fixture(100);   // EMAIL permanently down
        InProcessEventListener l = new InProcessEventListener(f.processor(), new RetryPolicy(3, 0, 1.0, 0), 0);
        DomainEvent e = TestSupport.event(UUID.randomUUID(), "ticket.created", UUID.randomUUID());

        l.onEvent(e);   // must not throw into core-service

        assertThat(f.email().calls.get()).isEqualTo(3);
        assertThat(f.inApp().calls.get()).isEqualTo(1);
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DEAD");
    }

    @Test
    void inProcessListenerRecoversAfterTransientFailure() {
        TestSupport.Fixture f = TestSupport.fixture(2);
        InProcessEventListener l = new InProcessEventListener(f.processor(), new RetryPolicy(5, 0, 1.0, 0), 0);
        DomainEvent e = TestSupport.event(UUID.randomUUID(), "ticket.created", UUID.randomUUID());
        l.onEvent(e);
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DONE");
        assertThat(f.store().deliveriesForEvent(e.eventId())).allMatch(d -> d.status() == DeliveryStatus.SENT);
    }

    @Test
    void inProcessListenerSwallowsMalformedEvents() {
        TestSupport.Fixture f = TestSupport.fixture(0);
        InProcessEventListener l = new InProcessEventListener(f.processor(), new RetryPolicy(3, 0, 1.0, 0), 0);
        l.onEvent(null);
        l.onEvent(new DomainEvent(null, "x", 1, null, null, null, null));
        assertThat(f.jdbc().queryForObject("select count(*) from notif_processed_event", Integer.class)).isZero();
        assertThat(List.of(Clock.systemUTC())).isNotEmpty();
    }
}
