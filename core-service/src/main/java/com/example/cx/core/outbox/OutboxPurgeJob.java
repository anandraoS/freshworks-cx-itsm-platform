package com.example.cx.core.outbox;

import com.example.cx.core.config.CxProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Housekeeping for {@code outbox_event}: deletes rows that were delivered (status SENT) more than
 * {@code cx.outbox.retention-days} (default 7) ago. Work per run is bounded: at most
 * {@code purge-batch-size} rows per delete and {@code purge-max-batches-per-run} deletes, each in its own short transaction, so
 * a large backlog drains over several runs without holding long locks. PENDING / PROCESSING / FAILED rows are never touched
 * (FAILED rows are kept for inspection). System job: the outbox is deliberately not tenant-scoped.
 */
@Component
public class OutboxPurgeJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeJob.class);

    private final OutboxRepository repo;
    private final CxProperties props;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public OutboxPurgeJob(OutboxRepository repo, CxProperties props, MeterRegistry meters, PlatformTransactionManager txm) {
        this.repo = repo;
        this.props = props;
        this.meters = meters;
        this.tx = new TransactionTemplate(txm);
    }

    @Scheduled(fixedDelayString = "${cx.outbox.purge-interval-ms:3600000}", initialDelayString = "${cx.outbox.purge-interval-ms:3600000}")
    public void scheduled() {
        if (!props.outbox().purgeEnabled()) return;
        try {
            int n = purgeSentBefore(Instant.now().minus(Duration.ofDays(props.outbox().retentionDays())));
            if (n > 0) log.info("Purged {} delivered outbox rows older than {} day(s)", n, props.outbox().retentionDays());
        } catch (RuntimeException e) {
            log.warn("Outbox purge failed: {}", e.toString());
        }
    }

    /** @return rows deleted in this run (bounded by batch size x max batches) */
    public int purgeSentBefore(Instant before) {
        int batch = Math.max(1, props.outbox().purgeBatchSize());
        int maxBatches = Math.max(1, props.outbox().purgeMaxBatchesPerRun());
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            List<UUID> ids = repo.findSentIdsBefore(before, PageRequest.of(0, batch));
            if (ids.isEmpty()) break;
            Integer deleted = tx.execute(s -> repo.deleteSentByIds(ids));
            total += deleted == null ? 0 : deleted;
            if (ids.size() < batch) break;
        }
        if (total > 0) meters.counter("cx.outbox.purged").increment(total);
        return total;
    }
}
