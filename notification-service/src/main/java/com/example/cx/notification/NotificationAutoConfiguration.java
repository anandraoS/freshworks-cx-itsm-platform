package com.example.cx.notification;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Library mode: registers the whole notification module inside a host app (core-service) when
 * {@code cx.notifications.embedded=true}. Registered in META-INF/spring/...AutoConfiguration.imports.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "cx.notifications", name = "embedded", havingValue = "true")
@Import(NotificationConfiguration.class)
public class NotificationAutoConfiguration {
}
