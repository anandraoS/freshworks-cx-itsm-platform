package com.example.cx.core.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DOCKER-ONLY (tagged "docker", excluded by default; run with -Pdocker-tests on a machine with Docker).
 * NOT EXECUTED in the authoring environment (no Docker available): this verifies, against a real PostgreSQL, that the portable
 * migrations + the PG-only migrations apply, the partial / GIN indexes exist, row-level security isolates tenants per
 * transaction (SET LOCAL semantics), fails closed without a tenant, and audit_event is append-only.
 */
@Tag("docker")
@Testcontainers
class PostgresRlsDockerIT {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String APP_ROLE = "cx_app_test";
    private static final String APP_ROLE_PW = "cx-app-test-only"; // ephemeral container, throw-away value

    static UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();

    private static boolean prepared;

    private static synchronized void migrateAndPrepare() throws Exception {
        if (prepared) return;
        prepared = true;
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration", "classpath:db/migration-pg").load().migrate();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); Statement s = c.createStatement()) {
            s.execute("create role " + APP_ROLE + " login password '" + APP_ROLE_PW + "' nosuperuser nobypassrls");
            s.execute("grant select, insert, update, delete on all tables in schema public to " + APP_ROLE);
            for (UUID t : new UUID[]{tenantA, tenantB})
                s.execute("insert into organization (id, slug, name, created_at) values ('" + t + "', 'slug-" + t.toString().substring(0, 8) + "', 'Org', now())");
        }
    }

    private static Connection appConnection() throws Exception {
        return DriverManager.getConnection(PG.getJdbcUrl(), APP_ROLE, APP_ROLE_PW);
    }

    private static void insertUser(Connection c, UUID tenant, String email) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("insert into app_user (id, tenant_id, email, display_name, password_hash, roles, active, created_at) "
                + "values (?, ?, ?, 'U', 'x', ',AGENT,', true, now())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setString(3, email);
            ps.executeUpdate();
        }
    }

    private static int countUsers(Connection c) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select count(*) from app_user")) { rs.next(); return rs.getInt(1); }
    }

    private static void bindTenant(Connection c, UUID tenant) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) { // true = transaction-local
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }

    @Test
    void migrationsApplyAndRlsIsolatesTenants() throws Exception {
        migrateAndPrepare();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("select count(*) from pg_indexes where indexname in ('ix_ticket_open_queue','ix_ticket_fts','ix_ticket_title_trgm')");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, tenantA);
            insertUser(c, tenantA, "a@x.test");
            c.commit();
            bindTenant(c, tenantB);
            insertUser(c, tenantB, "b@x.test");
            c.commit();

            // transaction 1 as tenant A: sees only A
            bindTenant(c, tenantA);
            assertThat(countUsers(c)).isEqualTo(1);
            // a write for another tenant violates the WITH CHECK policy
            assertThatThrownBy(() -> insertUser(c, tenantB, "intruder@x.test")).hasMessageContaining("row-level security");
            c.rollback();

            // SET LOCAL semantics: after commit/rollback the binding is gone -> fail closed (no rows), no leak to the next borrower
            assertThat(countUsers(c)).isZero();
            c.commit();
            bindTenant(c, tenantB);
            assertThat(countUsers(c)).isEqualTo(1);
            c.commit();
        }
    }

    @Test
    void auditEventIsAppendOnlyInTheDatabase() throws Exception {
        migrateAndPrepare();
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, tenantA);
            UUID id = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement("insert into audit_event (id, tenant_id, occurred_at, action, entity_type) values (?, ?, now(), 'X', 'Y')")) {
                ps.setObject(1, id);
                ps.setObject(2, tenantA);
                ps.executeUpdate();
            }
            c.commit();
            bindTenant(c, tenantA);
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) { s.executeUpdate("update audit_event set action = 'TAMPERED'"); }
            }).hasMessageContaining("append-only");
            c.rollback();
        }
    }
}
