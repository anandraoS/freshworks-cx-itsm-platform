package com.example.cx.notification.rabbit;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.NotificationProcessor;
import com.example.cx.notification.ProcessingResult;
import com.example.cx.notification.RetryPolicy;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

/**
 * Manual-ack consumer logic for queue {@code cx.notifications}.
 * <ul>
 *   <li>success or duplicate: {@code basicAck};</li>
 *   <li>unparseable / non-retryable: {@code basicReject(requeue=false)} so the broker dead-letters it via
 *       {@code cx.events.dlx} to {@code cx.notifications.dlq};</li>
 *   <li>retryable failure and attempts left: republish (persistent) to {@code cx.notifications.retry} with header
 *       {@code x-attempt}+1 and wait for the publisher confirm, THEN ack the original (never lose the message; if the
 *       republish is not confirmed the original is requeued). The retry queue's TTL dead-letters it back into
 *       {@code cx.notifications};</li>
 *   <li>retries exhausted: persist DEAD state, then reject (requeue=false) -&gt; DLQ.</li>
 * </ul>
 */
public class NotificationMessageHandler implements ChannelAwareMessageListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationMessageHandler.class);
    public static final String ATTEMPT_HEADER = "x-attempt";

    private final NotificationProcessor processor;
    private final RetryPolicy policy;
    private final ObjectMapper mapper;
    private final long confirmTimeoutMs;

    public NotificationMessageHandler(NotificationProcessor processor, RetryPolicy policy, long confirmTimeoutMs) {
        this.processor = processor;
        this.policy = policy;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.mapper = new ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        DomainEvent event;
        try {
            event = mapper.readValue(message.getBody(), DomainEvent.class);
        } catch (Exception parse) {
            log.error("Unparseable event message (tag {}), dead-lettering: {}", tag, parse.toString());
            channel.basicReject(tag, false);
            return;
        }

        ProcessingResult result;
        try {
            result = processor.process(event);
        } catch (Exception failure) {
            int failedAttempt = priorAttempts(message.getMessageProperties()) + 1;
            RetryPolicy.Decision decision = policy.decide(failedAttempt, failure);
            if (decision.retry()) {
                retryLater(message, channel, tag, failedAttempt, decision, event, failure);
            } else {
                deadLetter(channel, tag, event, failure, failedAttempt);
            }
            return;
        }
        log.debug("Event {} -> {}", event.eventId(), result);
        // outside the try: an ack I/O error must not be mistaken for a processing failure (redelivery is a safe no-op)
        channel.basicAck(tag, false);
    }

    private void retryLater(Message message, Channel channel, long tag, int failedAttempt, RetryPolicy.Decision decision,
                            DomainEvent event, Exception failure) throws Exception {
        log.warn("Event {} attempt {}/{} failed ({}), scheduling retry", event.eventId(), failedAttempt, policy.maxAttempts(), failure.toString());
        try {
            Map<String, Object> headers = new HashMap<>();
            headers.put(ATTEMPT_HEADER, failedAttempt);
            AMQP.BasicProperties.Builder props = new AMQP.BasicProperties.Builder()
                    .deliveryMode(2)                       // persistent
                    .contentType(message.getMessageProperties().getContentType())
                    .correlationId(message.getMessageProperties().getCorrelationId())
                    .headers(headers);
            if (policy.perMessageDelay()) props.expiration(Long.toString(decision.delayMillis()));
            channel.confirmSelect();
            channel.basicPublish("", com.example.cx.events.DomainEvent.NOTIFICATION_RETRY_QUEUE, false, props.build(), message.getBody());
            channel.waitForConfirmsOrDie(confirmTimeoutMs);
        } catch (Exception publishFailure) {
            log.error("Could not schedule retry for event {}; requeueing original", event.eventId(), publishFailure);
            channel.basicNack(tag, false, true);
            return;
        }
        channel.basicAck(tag, false);
    }

    private void deadLetter(Channel channel, long tag, DomainEvent event, Exception failure, int attempts) throws Exception {
        log.error("Event {} dead-lettered after {} attempt(s): {}", event.eventId(), attempts, failure.toString());
        try {
            processor.markDead(event, failure);
        } catch (Exception e) {
            log.error("Could not persist DEAD state for event {}", event.eventId(), e);
        }
        channel.basicReject(tag, false);
    }

    /** Failed attempts so far: our own header, falling back to the broker's x-death count for the retry queue. */
    static int priorAttempts(MessageProperties props) {
        Object h = props.getHeaders().get(ATTEMPT_HEADER);
        if (h instanceof Number n) return n.intValue();
        Object death = props.getHeaders().get("x-death");
        if (death instanceof List<?> list) {
            long total = 0;
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && com.example.cx.events.DomainEvent.NOTIFICATION_RETRY_QUEUE.equals(m.get("queue"))
                        && m.get("count") instanceof Number c) {
                    total += c.longValue();
                }
            }
            return (int) total;
        }
        return 0;
    }
}
