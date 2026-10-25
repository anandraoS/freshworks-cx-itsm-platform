package com.example.cx.notification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads core-service's {@code app_user} table (same database in embedded mode and in the docker deployment, where both
 * services use the one PostgreSQL). A standalone notification-service with its own empty database has no such table:
 * the lookup then reports "unavailable" (not an error) and only the payload tenant-consistency check applies.
 *
 * <p>On PostgreSQL the tenant is bound per transaction ({@code set_config('app.tenant_id', ?, true)}, the same mechanism
 * core-service uses) because {@code app_user} is under row level security there. That PostgreSQL path has NOT been executed
 * in the authoring environment (no Docker).
 */
public class JdbcRecipientDirectory implements RecipientDirectory {
    private static final Logger log = LoggerFactory.getLogger(JdbcRecipientDirectory.class);
    private static final int CHUNK = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean postgres;
    private final AtomicBoolean warned = new AtomicBoolean();

    public JdbcRecipientDirectory(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        boolean pg;
        try (var c = dataSource.getConnection()) {
            pg = c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) {
            pg = false;
        }
        this.postgres = pg;
    }

    @Override
    public Optional<Set<UUID>> usersInTenant(UUID tenantId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) return Optional.of(Set.of());
        try {
            Set<UUID> found = new LinkedHashSet<>();
            List<UUID> all = new ArrayList<>(userIds);
            for (int i = 0; i < all.size(); i += CHUNK) {
                List<UUID> part = all.subList(i, Math.min(all.size(), i + CHUNK));
                found.addAll(tx.execute(s -> {
                    if (postgres) jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> { }, tenantId.toString());
                    String marks = String.join(",", Collections.nCopies(part.size(), "?"));
                    Object[] args = new Object[part.size() + 1];
                    args[0] = tenantId;
                    for (int k = 0; k < part.size(); k++) args[k + 1] = part.get(k);
                    return jdbc.query("select id from app_user where tenant_id = ? and id in (" + marks + ")",
                            (rs, n) -> rs.getObject(1, UUID.class), args);
                }));
            }
            return Optional.of(found);
        } catch (BadSqlGrammarException noTable) {
            if (warned.compareAndSet(false, true)) {
                log.info("app_user is not reachable from this notification deployment: recipient membership is not verified "
                        + "(payload tenant consistency is still enforced)");
            }
            return Optional.empty();
        }
    }
}
