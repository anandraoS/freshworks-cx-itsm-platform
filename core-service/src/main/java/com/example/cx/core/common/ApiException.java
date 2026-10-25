package com.example.cx.core.common;

import java.util.List;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final List<FieldErrorDto> fieldErrors;

    public ApiException(ErrorCode code, String message) { this(code, message, List.of()); }

    public ApiException(ErrorCode code, String message, List<FieldErrorDto> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ErrorCode code() { return code; }
    public List<FieldErrorDto> fieldErrors() { return fieldErrors; }

    /** 404 is also used for cross-tenant ids: callers must never reveal whether the id exists elsewhere. */
    public static ApiException notFound(String what) { return new ApiException(ErrorCode.NOT_FOUND, what + " not found"); }
    public static ApiException forbidden(String msg) { return new ApiException(ErrorCode.FORBIDDEN, msg); }
    public static ApiException validation(String field, String msg) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(new FieldErrorDto(field, msg)));
    }
    public static ApiException validation(List<FieldErrorDto> errs) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Validation failed", errs);
    }
    public static ApiException conflict(String msg) { return new ApiException(ErrorCode.CONFLICT, msg); }
}
