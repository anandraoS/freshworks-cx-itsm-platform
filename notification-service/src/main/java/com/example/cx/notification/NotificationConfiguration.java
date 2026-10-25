package com.example.cx.notification;

import com.example.cx.events.EventListener;
import com.example.cx.notification.channel.ChannelSender;
import com.example.cx.notification.channel.EmailChannelSender;
import com.example.cx.notification.channel.InAppChannelSender;
import com.example.cx.notification.security.JwtAuthInterceptor;
import com.example.cx.notification.security.JwtVerifier;
import com.example.cx.notification.store.NotificationStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Self-contained module configuration, used both by the standalone app and (via {@link NotificationAutoConfiguration})
 * when embedded in core-service. All bean names are prefixed {@code cx...} to avoid clashes with a host application.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
@ComponentScan(basePackageClasses = NotificationConfiguration.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.cx\\.notification\\.rabbit\\..*"),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {NotificationServiceApplication.class, NotificationAutoConfiguration.class, NotificationConfiguration.class})})
public class NotificationConfiguration {

    @Bean("cxNotificationMigrations")
    NotificationMigrations cxNotificationMigrations(DataSource dataSource) {
        return new NotificationMigrations(dataSource);
    }

    @Bean("cxNotificationStore")
    NotificationStore cxNotificationStore(DataSource dataSource, NotificationMigrations migrations) {
        return new NotificationStore(dataSource, Clock.systemUTC());
    }

    @Bean("cxNotificationTemplates")
    NotificationTemplates cxNotificationTemplates() { return new NotificationTemplates(); }

    @Bean("cxInAppChannelSender")
    ChannelSender cxInAppChannelSender() { return new InAppChannelSender(); }

    @Bean("cxEmailChannelSender")
    ChannelSender cxEmailChannelSender() { return new EmailChannelSender(); }

    @Bean("cxNotificationRetryPolicy")
    RetryPolicy cxNotificationRetryPolicy(NotificationProperties p) {
        return new RetryPolicy(p.getMaxAttempts(), p.getRetryDelayMs(), p.getBackoffMultiplier(), p.getMaxRetryDelayMs());
    }

    @Bean("cxNotificationRecipientDirectory")
    RecipientDirectory cxNotificationRecipientDirectory(DataSource dataSource) { return new JdbcRecipientDirectory(dataSource); }

    @Bean("cxNotificationProcessor")
    NotificationProcessor cxNotificationProcessor(NotificationStore store, NotificationTemplates templates, List<ChannelSender> senders,
                                                  NotificationProperties props, ObjectProvider<MeterRegistry> registry,
                                                  RecipientDirectory directory) {
        return new NotificationProcessor(store, templates, senders, props.getChannels(), new NotificationMetrics(registry.getIfAvailable()),
                props.isValidateRecipients() ? directory : null);
    }

    /** Picked up by core-service's in-memory event dispatcher in local mode (it collects all EventListener beans). */
    @Bean("cxNotificationEventListener")
    EventListener cxNotificationEventListener(NotificationProcessor processor, RetryPolicy policy, NotificationProperties props) {
        return new InProcessEventListener(processor, policy, props.getLocalRetryDelayMs());
    }

    @Bean("cxNotificationJwtVerifier")
    JwtVerifier cxNotificationJwtVerifier(
            @Value("${cx.security.jwt.secret}") String secret) {
        return new JwtVerifier(secret);
    }

    @Bean("cxNotificationJwtInterceptor")
    JwtAuthInterceptor cxNotificationJwtInterceptor(JwtVerifier verifier) { return new JwtAuthInterceptor(verifier); }

    /** RabbitMQ consumer: only in docker profile ({@code cx.notifications.rabbit.enabled=true}) and only if spring-amqp is present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
    @ConditionalOnProperty(prefix = "cx.notifications.rabbit", name = "enabled", havingValue = "true")
    @Import(com.example.cx.notification.rabbit.NotificationRabbitConfiguration.class)
    static class RabbitConsumerImport {}
}
