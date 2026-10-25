package com.example.cx.notification.channel;

import com.example.cx.notification.NotificationChannel;
import com.example.cx.notification.store.NotificationStore.PendingDelivery;

/** The notif_notification row IS the in-app message; "sending" just acknowledges that it exists. */
public class InAppChannelSender implements ChannelSender {
    @Override
    public NotificationChannel channel() { return NotificationChannel.IN_APP; }

    @Override
    public void send(PendingDelivery delivery) { /* row already persisted by the processor */ }
}
