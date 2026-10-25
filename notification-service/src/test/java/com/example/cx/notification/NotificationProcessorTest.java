package com.example.cx.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.TestSupport.Fixture;
import com.example.cx.notification.store.NotificationStore.DeliveryRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationProcessorTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private int count(Fixture f, String table) {
        return f.jdbc().queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void processesEventIntoNotificationsAndSentDeliveries() {
        Fixture f = TestSupport.fixture(0);
        DomainEvent e = TestSupport.event(tenant, "ticket.created", alice, bob);

        assertThat(f.processor().process(e)).isEqualTo(ProcessingResult.PROCESSED);

        assertThat(count(f, "notif_notification")).isEqualTo(2);
        assertThat(count(f, "notif_delivery")).isEqualTo(4);   // 2 users x (IN_APP, EMAIL)
        assertThat(f.store().deliveriesForEvent(e.eventId())).allMatch(d -> d.status() == DeliveryStatus.SENT && d.attempts() == 1);
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DONE");
        assertThat(f.jdbc().queryForObject("select count(*) from notif_notification where tenant_id = ?", Integer.class, tenant)).isEqualTo(2);
    }

    @Test
    void duplicateDeliveryIsANoOp() {
        Fixture f = TestSupport.fixture(0);
        DomainEvent e = TestSupport.event(tenant, "ticket.assigned", alice);

        assertThat(f.processor().process(e)).isEqualTo(ProcessingResult.PROCESSED);
        int emailCallsAfterFirst = f.email().calls.get();
        assertThat(f.processor().process(e)).isEqualTo(ProcessingResult.DUPLICATE);
        assertThat(f.processor().process(e)).isEqualTo(ProcessingResult.DUPLICATE);

        assertThat(count(f, "notif_notification")).isEqualTo(1);
        assertThat(count(f, "notif_processed_event")).isEqualTo(1);
        assertThat(f.email().calls.get()).isEqualTo(emailCallsAfterFirst);
    }

    @Test
    void uniqueConstraintBacksIdempotency() {
        Fixture f = TestSupport.fixture(0);
        DomainEvent e = TestSupport.event(tenant, "ticket.created", alice);
        f.processor().process(e);
        assertThatThrownBy(() -> f.jdbc().update(
                "insert into notif_processed_event (event_id, tenant_id, event_type, status, attempts, first_seen_at) values (?,?,?,?,?,current_timestamp)",
                e.eventId(), tenant, "x", "DONE", 1))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void failedChannelIsRecordedAndRetryOnlyResendsWhatFailed() {
        Fixture f = TestSupport.fixture(1);   // first EMAIL send fails
        DomainEvent e = TestSupport.event(tenant, "ticket.escalated", alice);

        assertThatThrownBy(() -> f.processor().process(e)).isInstanceOf(NotificationDeliveryException.class);
        List<DeliveryRow> afterFailure = f.store().deliveriesForEvent(e.eventId());
        assertThat(afterFailure).extracting(DeliveryRow::channel, DeliveryRow::status)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("IN_APP", DeliveryStatus.SENT),
                        org.assertj.core.groups.Tuple.tuple("EMAIL", DeliveryStatus.FAILED));
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("PROCESSING");

        // redelivery (retry): must NOT duplicate the in-app notification or re-send IN_APP
        assertThat(f.processor().process(e)).isEqualTo(ProcessingResult.PROCESSED);
        assertThat(f.inApp().calls.get()).isEqualTo(1);
        assertThat(f.email().calls.get()).isEqualTo(2);
        assertThat(count(f, "notif_notification")).isEqualTo(1);
        assertThat(f.store().deliveriesForEvent(e.eventId())).allMatch(d -> d.status() == DeliveryStatus.SENT);
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DONE");
    }

    @Test
    void markDeadRecordsDeadDeliveriesAndLaterRedeliveryIsIgnored() {
        Fixture f = TestSupport.fixture(100);   // email always fails
        DomainEvent e = TestSupport.event(tenant, "ticket.sla.breached", alice);
        assertThatThrownBy(() -> f.processor().process(e)).isInstanceOf(NotificationDeliveryException.class);

        f.processor().markDead(e, new IllegalStateException("gave up"));

        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DEAD");
        assertThat(f.store().deliveriesForEvent(e.eventId())).extracting(DeliveryRow::channel, DeliveryRow::status)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("IN_APP", DeliveryStatus.SENT),
                        org.assertj.core.groups.Tuple.tuple("EMAIL", DeliveryStatus.DEAD));
        assertThat(f.processor().process(e)).isEqualTo(ProcessingResult.DUPLICATE);
    }

    @Test
    void markDeadForEventThatNeverGotRowsCreatesEventLevelDeadRecord() {
        Fixture f = TestSupport.fixture(0);
        DomainEvent e = TestSupport.event(tenant, "ticket.created", alice);
        f.processor().markDead(e, new RuntimeException("db down"));
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DEAD");
        assertThat(f.store().deliveriesForEvent(e.eventId())).singleElement()
                .satisfies(d -> { assertThat(d.channel()).isEqualTo("ALL"); assertThat(d.status()).isEqualTo(DeliveryStatus.DEAD); });
    }

    @Test
    void noRecipientsCompletesWithoutRows() {
        Fixture f = TestSupport.fixture(0);
        DomainEvent e = new DomainEvent(UUID.randomUUID(), "ticket.created", 1, tenant, null, Instant.now(), Map.of("recipientUserIds", "not-a-uuid, "));
        assertThat(f.processor().process(e)).isEqualTo(ProcessingResult.NO_RECIPIENTS);
        assertThat(count(f, "notif_notification")).isZero();
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DONE");
    }

    @Test
    void malformedEventsAreNonRetryable() {
        Fixture f = TestSupport.fixture(0);
        assertThatThrownBy(() -> f.processor().process(null)).isInstanceOf(NonRetryableException.class);
        assertThatThrownBy(() -> f.processor().process(new DomainEvent(null, "t", 1, tenant, null, Instant.now(), Map.of())))
                .isInstanceOf(NonRetryableException.class);
        assertThatThrownBy(() -> f.processor().process(new DomainEvent(UUID.randomUUID(), "t", 1, null, null, Instant.now(), Map.of())))
                .isInstanceOf(NonRetryableException.class);
        assertThatThrownBy(() -> f.processor().process(new DomainEvent(UUID.randomUUID(), " ", 1, tenant, null, Instant.now(), Map.of())))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void notificationsCarryTenantAndAreStoredPerTenant() {
        Fixture f = TestSupport.fixture(0);
        UUID otherTenant = UUID.randomUUID();
        f.processor().process(TestSupport.event(tenant, "ticket.created", alice));
        f.processor().process(TestSupport.event(otherTenant, "ticket.created", alice));   // same user id, other tenant
        assertThat(f.store().count(tenant, alice, false)).isEqualTo(1);
        assertThat(f.store().count(otherTenant, alice, false)).isEqualTo(1);
        assertThat(f.store().count(UUID.randomUUID(), alice, false)).isZero();
    }
}
