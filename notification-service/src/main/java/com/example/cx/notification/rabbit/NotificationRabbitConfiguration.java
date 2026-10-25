package com.example.cx.notification.rabbit;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.NotificationProcessor;
import com.example.cx.notification.NotificationProperties;
import com.example.cx.notification.RetryPolicy;
import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology + listener container. Imported only when {@code cx.notifications.rabbit.enabled=true} (docker profile).
 *
 * <pre>
 * cx.events (topic, durable) --ticket.# / ai.#--&gt; cx.notifications (durable, x-dead-letter-exchange=cx.events.dlx)
 * cx.notifications.retry (durable, x-message-ttl, DLX = default exchange, DL routing key = cx.notifications)
 * cx.events.dlx (fanout, durable) --&gt; cx.notifications.dlq (durable)
 * </pre>
 * Routing keys use {@code ticket.#}/{@code ai.#} (not {@code .*}) because types such as ticket.sla.breached and
 * ai.suggestion.decided have three words.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationRabbitConfiguration {

    @Bean
    TopicExchange cxEventsExchange() { return ExchangeBuilder.topicExchange(DomainEvent.EXCHANGE).durable(true).build(); }

    @Bean
    FanoutExchange cxEventsDlx() { return new FanoutExchange(DomainEvent.DLX, true, false); }

    @Bean
    Queue cxNotificationsQueue() {
        return QueueBuilder.durable(DomainEvent.NOTIFICATION_QUEUE).withArgument("x-dead-letter-exchange", DomainEvent.DLX).build();
    }

    @Bean
    Queue cxNotificationsRetryQueue(NotificationProperties props) {
        return QueueBuilder.durable(DomainEvent.NOTIFICATION_RETRY_QUEUE)
                .withArguments(Map.of("x-message-ttl", props.getRetryDelayMs(),
                        "x-dead-letter-exchange", "",
                        "x-dead-letter-routing-key", DomainEvent.NOTIFICATION_QUEUE))
                .build();
    }

    @Bean
    Queue cxNotificationsDlq() { return QueueBuilder.durable(DomainEvent.NOTIFICATION_DLQ).build(); }

    @Bean
    Declarables cxNotificationBindings(TopicExchange cxEventsExchange, FanoutExchange cxEventsDlx,
                                       Queue cxNotificationsQueue, Queue cxNotificationsDlq) {
        Binding ticket = BindingBuilder.bind(cxNotificationsQueue).to(cxEventsExchange).with("ticket.#");
        Binding ai = BindingBuilder.bind(cxNotificationsQueue).to(cxEventsExchange).with("ai.#");
        Binding dlq = BindingBuilder.bind(cxNotificationsDlq).to(cxEventsDlx);
        return new Declarables(ticket, ai, dlq);
    }

    @Bean
    RabbitAdmin cxNotificationRabbitAdmin(ConnectionFactory cf) {
        RabbitAdmin admin = new RabbitAdmin(cf);
        admin.setAutoStartup(true);
        return admin;
    }

    @Bean
    NotificationMessageHandler cxNotificationMessageHandler(NotificationProcessor processor, RetryPolicy policy, NotificationProperties props) {
        return new NotificationMessageHandler(processor, policy, props.getRabbit().getConfirmTimeoutMs());
    }

    @Bean
    SimpleMessageListenerContainer cxNotificationListenerContainer(ConnectionFactory cf, NotificationMessageHandler handler,
                                                                   NotificationProperties props, RabbitAdmin cxNotificationRabbitAdmin) {
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(cf);
        c.setQueueNames(DomainEvent.NOTIFICATION_QUEUE);
        c.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        c.setPrefetchCount(props.getRabbit().getPrefetch());
        c.setConcurrentConsumers(props.getRabbit().getConcurrency());
        c.setMaxConcurrentConsumers(props.getRabbit().getMaxConcurrency());
        c.setDefaultRequeueRejected(false);
        c.setMissingQueuesFatal(false);
        c.setAmqpAdmin(cxNotificationRabbitAdmin);
        c.setMessageListener(handler);
        return c;
    }
}
