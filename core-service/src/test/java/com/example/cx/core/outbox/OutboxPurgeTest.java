package com.example.cx.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"cx.outbox.purge-batch-size=3", "cx.outbox.purge-max-batches-per-run=2", "cx.outbox.retention-days=7"})
class OutboxPurgeTest extends AbstractApiTest {
    @Autowired OutboxPurgeJob job;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() throws Exception {
        tenantId = newTenant().id();   // outbox_event.tenant_id has a foreign key to organization
        jdbc.update("delete from outbox_event");
    }

    private UUID tenantId;

    private UUID insert(String status, Instant sentAt) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into outbox_event (id, tenant_id, event_type, payload, status, attempts, next_attempt_at, created_at, sent_at) "
                        + "values (?,?,?,?,?,?,?,?,?)", id, tenantId, "ticket.created", "{}", status, 0,
                Timestamp.from(now), Timestamp.from(now.minus(Duration.ofDays(30))), sentAt == null ? null : Timestamp.from(sentAt));
        return id;
    }

    private long count(String status) { return jdbc.queryForObject("select count(*) from outbox_event where status = ?", Long.class, status); }

    @Test
    void deletesOnlyOldSentRowsAndLeavesEverythingElse() {
        Instant old = Instant.now().minus(Duration.ofDays(10)), fresh = Instant.now().minus(Duration.ofDays(2));
        insert("SENT", old);
        insert("SENT", old);
        UUID recent = insert("SENT", fresh);
        insert("PENDING", null);
        insert("PROCESSING", null);
        insert("FAILED", null);

        int deleted = job.purgeSentBefore(Instant.now().minus(Duration.ofDays(7)));

        assertThat(deleted).isEqualTo(2);
        assertThat(count("SENT")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where id = ?", Long.class, recent)).isEqualTo(1);
        assertThat(count("PENDING")).isEqualTo(1);
        assertThat(count("PROCESSING")).isEqualTo(1);
        assertThat(count("FAILED")).isEqualTo(1);
    }

    @Test
    void workIsBoundedPerRunAndTheBacklogDrainsOverSeveralRuns() {
        Instant old = Instant.now().minus(Duration.ofDays(30));
        for (int i = 0; i < 20; i++) insert("SENT", old);

        // batch size 3 x max 2 batches per run = at most 6 rows per run
        assertThat(job.purgeSentBefore(Instant.now().minus(Duration.ofDays(7)))).isEqualTo(6);
        assertThat(count("SENT")).isEqualTo(14);
        int runs = 1;
        while (count("SENT") > 0 && runs < 10) { job.purgeSentBefore(Instant.now().minus(Duration.ofDays(7))); runs++; }
        assertThat(count("SENT")).isZero();
        assertThat(runs).isEqualTo(4);   // 6 + 6 + 6 + 2
    }

    @Test
    void scheduledEntryPointHonoursTheEnabledFlag() {
        insert("SENT", Instant.now().minus(Duration.ofDays(10)));
        job.scheduled();   // purge-enabled=false in the test profile: nothing happens
        assertThat(count("SENT")).isEqualTo(1);
    }
}
