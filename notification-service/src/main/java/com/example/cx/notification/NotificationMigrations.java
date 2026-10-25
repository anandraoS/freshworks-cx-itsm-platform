package com.example.cx.notification;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Runs this module's own Flyway migrations ({@code classpath:db/notification-migration}, history table
 * {@code notif_schema_history}) so it can share a database with core-service without touching core's migration set.
 * {@code baselineOnMigrate} is required because the shared schema is not empty when we run.
 */
public class NotificationMigrations {
    public static final String LOCATION = "classpath:db/notification-migration";
    public static final String HISTORY_TABLE = "notif_schema_history";

    public NotificationMigrations(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
