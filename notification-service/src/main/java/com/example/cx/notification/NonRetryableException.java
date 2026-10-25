package com.example.cx.notification;

/** Poison input (malformed event): retrying cannot help, go straight to the DLQ. */
public class NonRetryableException extends RuntimeException {
    public NonRetryableException(String message) { super(message); }
    public NonRetryableException(String message, Throwable cause) { super(message, cause); }
}
