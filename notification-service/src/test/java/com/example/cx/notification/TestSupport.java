package com.example.cx.notification;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.channel.ChannelSender;
import com.example.cx.notification.channel.InAppChannelSender;
import com.example.cx.notification.store.NotificationStore;
import com.example.cx.notification.store.NotificationStore.PendingDelivery;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class TestSupport {
    private TestSupport() {}

    public static DataSource freshDb() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:t" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new NotificationMigrations(ds);
        return ds;
    }

    public static DomainEvent event(UUID tenant, String type, UUID... recipients) {
        String ids = String.join(",", java.util.Arrays.stream(recipients).map(UUID::toString).toList());
        return new DomainEvent(UUID.randomUUID(), type, 1, tenant, "corr-1", Instant.now(),
                Map.of("recipientUserIds", ids, "ticketId", UUID.randomUUID().toString(), "ticketNumber", "T-42", "title", "Printer on fire"));
    }

    /** Sender that counts calls and can be told to fail the first N calls. */
    public static class CountingSender implements ChannelSender {
        private final NotificationChannel channel;
        public final AtomicInteger calls = new AtomicInteger();
        public final AtomicInteger failuresLeft = new AtomicInteger();

        public CountingSender(NotificationChannel channel, int failFirst) {
            this.channel = channel;
            this.failuresLeft.set(failFirst);
        }

        @Override public NotificationChannel channel() { return channel; }

        @Override
        public void send(PendingDelivery d) {
            calls.incrementAndGet();
            if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) throw new IllegalStateException("simulated " + channel + " outage");
        }
    }

    public record Fixture(DataSource ds, NotificationStore store, NotificationProcessor processor,
                          CountingSender inApp, CountingSender email, JdbcTemplate jdbc) {}

    public static Fixture fixture(int emailFailFirst) {
        DataSource ds = freshDb();
        NotificationStore store = new NotificationStore(ds, Clock.systemUTC());
        CountingSender inApp = new CountingSender(NotificationChannel.IN_APP, 0);
        CountingSender email = new CountingSender(NotificationChannel.EMAIL, emailFailFirst);
        NotificationProcessor p = new NotificationProcessor(store, new NotificationTemplates(), List.of(inApp, email),
                List.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL), new NotificationMetrics(null));
        return new Fixture(ds, store, p, inApp, email, new JdbcTemplate(ds));
    }

    public static ChannelSender realInApp() { return new InAppChannelSender(); }
}
