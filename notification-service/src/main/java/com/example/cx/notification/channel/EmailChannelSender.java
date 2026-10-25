package com.example.cx.notification.channel;

import com.example.cx.notification.NotificationChannel;
import com.example.cx.notification.store.NotificationStore.PendingDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SIMULATED email: logs and lets the processor record SENT. No SMTP / provider call is ever made. */
public class EmailChannelSender implements ChannelSender {
    private static final Logger log = LoggerFactory.getLogger(EmailChannelSender.class);

    @Override
    public NotificationChannel channel() { return NotificationChannel.EMAIL; }

    @Override
    public void send(PendingDelivery d) {
        // title is template output built from sanitized payload values (control chars stripped)
        log.info("SIMULATED EMAIL (not sent) tenant={} user={} subject=\"{}\"", d.tenantId(), d.userId(), d.title());
    }
}
