package com.example.cx.notification.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.cx.events.DomainEvent;
import com.example.cx.notification.DeliveryStatus;
import com.example.cx.notification.RetryPolicy;
import com.example.cx.notification.TestSupport;
import com.example.cx.notification.TestSupport.Fixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

/** Consumer behaviour against a fake AMQP channel (no broker): ack/reject/retry-publish decisions and recovery. */
class NotificationMessageHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private Message message(DomainEvent e, long tag, Integer attemptHeader) throws Exception {
        MessageProperties p = new MessageProperties();
        p.setDeliveryTag(tag);
        p.setContentType("application/json");
        if (attemptHeader != null) p.setHeader(NotificationMessageHandler.ATTEMPT_HEADER, attemptHeader);
        return MessageBuilder.withBody(MAPPER.writeValueAsBytes(e)).andProperties(p).build();
    }

    private NotificationMessageHandler handler(Fixture f, int maxAttempts) {
        return new NotificationMessageHandler(f.processor(), new RetryPolicy(maxAttempts, 1000, 1.0, 1000), 1000);
    }

    @Test
    void successIsAcked() throws Exception {
        Fixture f = TestSupport.fixture(0);
        Channel ch = mock(Channel.class);
        handler(f, 3).onMessage(message(TestSupport.event(tenant, "ticket.created", user), 7, null), ch);
        verify(ch).basicAck(7, false);
        verify(ch, never()).basicReject(anyLong(), anyBoolean());
        verify(ch, never()).basicPublish(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void duplicateIsAckedWithoutReprocessing() throws Exception {
        Fixture f = TestSupport.fixture(0);
        Channel ch = mock(Channel.class);
        DomainEvent e = TestSupport.event(tenant, "ticket.created", user);
        NotificationMessageHandler h = handler(f, 3);
        h.onMessage(message(e, 1, null), ch);
        h.onMessage(message(e, 2, null), ch);   // broker redelivery / publisher retry
        verify(ch).basicAck(1, false);
        verify(ch).basicAck(2, false);
        assertThat(f.email().calls.get()).isEqualTo(1);
    }

    @Test
    void poisonMessageIsRejectedWithoutRequeueSoBrokerDeadLetters() throws Exception {
        Fixture f = TestSupport.fixture(0);
        Channel ch = mock(Channel.class);
        Message poison = MessageBuilder.withBody("{not json".getBytes()).andProperties(new MessageProperties()).build();
        poison.getMessageProperties().setDeliveryTag(3);
        handler(f, 3).onMessage(poison, ch);
        verify(ch).basicReject(3, false);
        verify(ch, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void failureWithAttemptsLeftRepublishesPersistentToRetryQueueThenAcks() throws Exception {
        Fixture f = TestSupport.fixture(1);
        Channel ch = mock(Channel.class);
        handler(f, 3).onMessage(message(TestSupport.event(tenant, "ticket.created", user), 9, null), ch);

        ArgumentCaptor<AMQP.BasicProperties> props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        verify(ch).basicPublish(eq(""), eq(DomainEvent.NOTIFICATION_RETRY_QUEUE), eq(false), props.capture(), any(byte[].class));
        assertThat(props.getValue().getDeliveryMode()).isEqualTo(2);
        assertThat(((Number) props.getValue().getHeaders().get("x-attempt")).intValue()).isEqualTo(1);
        verify(ch).waitForConfirmsOrDie(1000);
        verify(ch).basicAck(9, false);
        verify(ch, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void exhaustedAttemptsMarkDeadAndRejectToDlq() throws Exception {
        Fixture f = TestSupport.fixture(100);
        Channel ch = mock(Channel.class);
        DomainEvent e = TestSupport.event(tenant, "ticket.created", user);
        // third and final failure (header says 2 failures already happened), maxAttempts=3
        handler(f, 3).onMessage(message(e, 11, 2), ch);

        verify(ch).basicReject(11, false);
        verify(ch, never()).basicPublish(any(), any(), anyBoolean(), any(), any());
        verify(ch, never()).basicAck(anyLong(), anyBoolean());
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DEAD");
        assertThat(f.store().deliveriesForEvent(e.eventId())).anyMatch(d -> d.status() == DeliveryStatus.DEAD);
    }

    @Test
    void unconfirmedRetryPublishRequeuesOriginalInsteadOfLosingIt() throws Exception {
        Fixture f = TestSupport.fixture(1);
        Channel ch = mock(Channel.class);
        doThrow(new java.util.concurrent.TimeoutException("no confirm")).when(ch).waitForConfirmsOrDie(anyLong());
        handler(f, 3).onMessage(message(TestSupport.event(tenant, "ticket.created", user), 5, null), ch);
        verify(ch).basicNack(5, false, true);
        verify(ch, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void priorAttemptsFallsBackToXDeathCountOfRetryQueue() {
        MessageProperties p = new MessageProperties();
        p.setHeader("x-death", java.util.List.of(
                java.util.Map.of("queue", DomainEvent.NOTIFICATION_RETRY_QUEUE, "count", 2L),
                java.util.Map.of("queue", "other", "count", 9L)));
        assertThat(NotificationMessageHandler.priorAttempts(p)).isEqualTo(2);
        assertThat(NotificationMessageHandler.priorAttempts(new MessageProperties())).isZero();
    }

    /** Simulated broker loop: consumer fails twice (email outage), recovers on 3rd delivery; exactly one notification. */
    @Test
    void consumerFailureAndRecoveryViaRetryQueue() throws Exception {
        Fixture f = TestSupport.fixture(2);   // EMAIL fails the first two sends
        DomainEvent e = TestSupport.event(tenant, "ticket.assigned", user);
        NotificationMessageHandler h = handler(f, 5);

        Deque<Message> retryQueue = new ArrayDeque<>();
        Channel ch = mock(Channel.class);
        doAnswer(inv -> {
            AMQP.BasicProperties bp = inv.getArgument(3);
            MessageProperties mp = new MessageProperties();
            bp.getHeaders().forEach(mp::setHeader);
            mp.setContentType(bp.getContentType());
            retryQueue.add(MessageBuilder.withBody(inv.getArgument(4)).andProperties(mp).build());
            return null;
        }).when(ch).basicPublish(eq(""), eq(DomainEvent.NOTIFICATION_RETRY_QUEUE), anyBoolean(), any(), any(byte[].class));

        Message current = message(e, 1, null);
        int deliveries = 0;
        long tag = 1;
        while (current != null && deliveries < 10) {
            deliveries++;
            current.getMessageProperties().setDeliveryTag(tag++);
            h.onMessage(current, ch);
            current = retryQueue.poll();   // TTL expiry puts it back on the main queue
        }

        assertThat(deliveries).isEqualTo(3);
        verify(ch, org.mockito.Mockito.times(2)).basicPublish(eq(""), eq(DomainEvent.NOTIFICATION_RETRY_QUEUE), anyBoolean(), any(), any(byte[].class));
        verify(ch).basicAck(3, false);
        verify(ch, never()).basicReject(anyLong(), anyBoolean());
        assertThat(f.store().eventStatus(e.eventId())).isEqualTo("DONE");
        assertThat(f.jdbc().queryForObject("select count(*) from notif_notification", Integer.class)).isEqualTo(1);
        assertThat(f.inApp().calls.get()).isEqualTo(1);   // in-app was not re-sent on retries
        assertThat(f.email().calls.get()).isEqualTo(3);
    }

    @Test
    void ackIoErrorIsNotTreatedAsProcessingFailureAndRedeliveryIsNoOp() throws Exception {
        Fixture f = TestSupport.fixture(0);
        Channel ch = mock(Channel.class);
        doThrow(new IOException("channel closed")).when(ch).basicAck(anyLong(), anyBoolean());
        DomainEvent e = TestSupport.event(tenant, "ticket.created", user);
        NotificationMessageHandler h = handler(f, 3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.onMessage(message(e, 1, null), ch)).isInstanceOf(IOException.class);
        verify(ch, never()).basicPublish(any(), any(), anyBoolean(), any(), any());

        Channel fresh = mock(Channel.class);
        h.onMessage(message(e, 2, null), fresh);   // broker redelivers on a new channel
        verify(fresh).basicAck(2, false);
        assertThat(f.email().calls.get()).isEqualTo(1);
    }
}
