package com.example.cx.notification.api;

import java.util.List;

/** Contract error schema (docs/API-CONTRACT.md). */
public record ApiError(String timestamp, int status, String error, String code, String message, String path,
                       String correlationId, List<FieldError> fieldErrors) {
    public record FieldError(String field, String message) {}
}
