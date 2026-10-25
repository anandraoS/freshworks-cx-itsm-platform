package com.example.cx.notification.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Scoped to the notification controller only, so it never changes the host application error handling. Highest precedence so that,
 * when embedded, an unscoped catch-all advice of the host (core-service handles Exception) cannot swallow this controller errors as 500.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(NotificationExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e, HttpServletRequest req) {
        return ResponseEntity.status(e.status()).body(ErrorWriter.build(e.status(), e.code(), e.getMessage(), req, e.fieldErrors()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(ErrorWriter.build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", req,
                List.of(new ApiError.FieldError(e.getName(), "invalid value"))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {}", req.getRequestURI(), e);
        return ResponseEntity.status(500).body(ErrorWriter.build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", req, List.of()));
    }
}
