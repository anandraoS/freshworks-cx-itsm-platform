package com.example.cx.notification;

/** At least one channel failed; the event may be retried (already-sent channels are skipped on retry). */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message, Throwable cause) { super(message, cause); }
}
