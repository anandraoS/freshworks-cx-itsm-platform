package com.example.cx.notification.channel;

import com.example.cx.notification.NotificationChannel;
import com.example.cx.notification.store.NotificationStore.PendingDelivery;

/** Delivery strategy for one channel. Throwing marks the delivery FAILED (retryable). */
public interface ChannelSender {
    NotificationChannel channel();

    void send(PendingDelivery delivery) throws Exception;
}
