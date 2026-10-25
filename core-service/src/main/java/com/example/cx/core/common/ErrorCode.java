package com.example.cx.core.common;

/** Machine-readable error codes from docs/API-CONTRACT.md (plus a few additive codes noted in the README). */
public enum ErrorCode {
    VALIDATION_FAILED(400, "BAD_REQUEST"),
    UNAUTHENTICATED(401, "UNAUTHORIZED"),
    FORBIDDEN(403, "FORBIDDEN"),
    NOT_FOUND(404, "NOT_FOUND"),
    METHOD_NOT_ALLOWED(405, "METHOD_NOT_ALLOWED"),
    UNSUPPORTED_MEDIA_TYPE(415, "UNSUPPORTED_MEDIA_TYPE"),
    TICKET_VERSION_CONFLICT(409, "CONFLICT"),
    DUPLICATE_SUBMISSION(409, "CONFLICT"),
    CONFLICT(409, "CONFLICT"),
    INVALID_TRANSITION(422, "UNPROCESSABLE_ENTITY"),
    RATE_LIMITED(429, "TOO_MANY_REQUESTS"),
    AI_UNAVAILABLE(503, "SERVICE_UNAVAILABLE"),
    INTERNAL_ERROR(500, "INTERNAL_SERVER_ERROR");

    private final int status;
    private final String error;

    ErrorCode(int status, String error) {
        this.status = status;
        this.error = error;
    }

    public int status() { return status; }
    public String error() { return error; }
}
