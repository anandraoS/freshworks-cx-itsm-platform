package com.example.cx.notification.api;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<ApiError.FieldError> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) { this(status, code, message, List.of()); }

    public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<ApiError.FieldError> fieldErrors() { return fieldErrors; }
}
