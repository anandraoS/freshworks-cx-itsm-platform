package com.example.cx.notification;

public enum ProcessingResult {
    /** Notifications created and all channels delivered. */
    PROCESSED,
    /** Event id already completed (or dead-lettered): nothing was done. */
    DUPLICATE,
    /** Event valid but no recipients in payload. */
    NO_RECIPIENTS
}
