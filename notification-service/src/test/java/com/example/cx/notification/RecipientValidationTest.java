package com.example.cx.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cx.events.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Recipients must belong to the event's tenant (when the user table is reachable); payload tenant must match the envelope. */
class RecipientValidationTest {

    private static TestSupport.Fixture withUsers(UUID tenantA, UUID userA, UUID tenantB, UUID userB, boolean createUserTable) {
        TestSupport.Fixture f = TestSupport.fixture(0);
        if (createUserTable) {
            f.jdbc().execute("create table app_user (id uuid primary key, tenant_id uuid not null)");
            f.jdbc().update("insert into app_user values (?,?)", userA, tenantA);
            f.jdbc().update("insert into app_user values (?,?)", userB, tenantB);
        }
        return f;
    }

    private static NotificationProcessor processorWithDirectory(TestSupport.Fixture f) {
        return new NotificationProcessor(f.store(), new NotificationTemplates(), List.of(f.inApp(), f.email()),
                List.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL), new NotificationMetrics(null),
                new JdbcRecipientDirectory(f.ds()));
    }

    @Test
    void recipientFromAnotherTenantIsDroppedAndNeverGetsANotification() {
        UUID tA = UUID.randomUUID(), tB = UUID.randomUUID(), uA = UUID.randomUUID(), uB = UUID.randomUUID();
        TestSupport.Fixture f = withUsers(tA, uA, tB, uB, true);
        NotificationProcessor p = processorWithDirectory(f);

        // event of tenant A lists its own user AND tenant B's user
        DomainEvent e = TestSupport.event(tA, "ticket.created", uA, uB);
        assertThat(p.process(e)).isEqualTo(ProcessingResult.PROCESSED);

        assertThat(f.store().count(tA, uA, false)).isEqualTo(1);
        assertThat(f.store().count(tB, uB, false)).isZero();
        assertThat(f.store().count(tA, uB, false)).isZero();
        assertThat(f.jdbc().queryForObject("select count(*) from notif_notification where user_id = ?", Integer.class, uB)).isZero();
    }

    @Test
    void eventWhoseOnlyRecipientsAreForeignCompletesWithNoRecipients() {
        UUID tA = UUID.randomUUID(), tB = UUID.randomUUID(), uA = UUID.randomUUID(), uB = UUID.randomUUID();
        TestSupport.Fixture f = withUsers(tA, uA, tB, uB, true);
        DomainEvent e = TestSupport.event(tA, "ticket.created", uB, UUID.randomUUID());
        assertThat(processorWithDirectory(f).process(e)).isEqualTo(ProcessingResult.NO_RECIPIENTS);
        assertThat(f.jdbc().queryForObject("select count(*) from notif_notification", Integer.class)).isZero();
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DONE");
    }

    @Test
    void whenTheUserTableIsNotReachableTheDirectoryAbstainsAndRecipientsAreKept() {
        TestSupport.Fixture f = TestSupport.fixture(0);   // no app_user table (standalone notification DB)
        JdbcRecipientDirectory dir = new JdbcRecipientDirectory(f.ds());
        UUID t = UUID.randomUUID(), u = UUID.randomUUID();
        assertThat(dir.usersInTenant(t, List.of(u))).isEmpty();
        assertThat(processorWithDirectory(f).process(TestSupport.event(t, "ticket.created", u))).isEqualTo(ProcessingResult.PROCESSED);
        assertThat(f.store().count(t, u, false)).isEqualTo(1);
    }

    @Test
    void payloadTenantMustMatchEnvelopeTenant() {
        TestSupport.Fixture f = TestSupport.fixture(0);
        UUID t = UUID.randomUUID(), other = UUID.randomUUID(), u = UUID.randomUUID();
        DomainEvent base = TestSupport.event(t, "ticket.created", u);

        Map<String, String> bad = new HashMap<>(base.payload());
        bad.put("tenantId", other.toString());
        DomainEvent forged = new DomainEvent(UUID.randomUUID(), base.type(), 1, t, "c", Instant.now(), bad);
        assertThatThrownBy(() -> f.processor().process(forged)).isInstanceOf(NonRetryableException.class).hasMessageContaining("tenantId");
        assertThat(f.store().count(t, u, false)).isZero();
        assertThat(f.store().eventStatus(forged.eventId())).isNull();   // rejected before it was even claimed

        Map<String, String> good = new HashMap<>(base.payload());
        good.put("tenantId", t.toString().toUpperCase());
        DomainEvent ok = new DomainEvent(UUID.randomUUID(), base.type(), 1, t, "c", Instant.now(), good);
        assertThat(f.processor().process(ok)).isEqualTo(ProcessingResult.PROCESSED);
        assertThat(Clock.systemUTC()).isNotNull();
        assertThat(new JdbcTemplate(f.ds()).queryForObject("select count(*) from notif_notification", Integer.class)).isEqualTo(1);
    }
}
