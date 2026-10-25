package com.example.cx.core.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e, HttpServletRequest r) {
        return respond(e.code(), e.getMessage(), r, e.fieldErrors(), null);
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ErrorResponse> rate(RateLimitedException e, HttpServletRequest r) {
        return respond(ErrorCode.RATE_LIMITED, e.getMessage(), r, null, String.valueOf(e.retryAfterSeconds()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e, HttpServletRequest r) {
        List<FieldErrorDto> f = e.getBindingResult().getFieldErrors().stream()
                .map(x -> new FieldErrorDto(x.getField(), x.getDefaultMessage())).toList();
        return respond(ErrorCode.VALIDATION_FAILED, "Validation failed", r, f, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> constraint(ConstraintViolationException e, HttpServletRequest r) {
        List<FieldErrorDto> f = e.getConstraintViolations().stream()
                .map(v -> new FieldErrorDto(v.getPropertyPath().toString(), v.getMessage())).toList();
        return respond(ErrorCode.VALIDATION_FAILED, "Validation failed", r, f, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> unreadable(Exception e, HttpServletRequest r) {
        return respond(ErrorCode.VALIDATION_FAILED, "Malformed request", r, null, null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> header(MissingRequestHeaderException e, HttpServletRequest r) {
        return respond(ErrorCode.VALIDATION_FAILED, "Validation failed", r,
                List.of(new FieldErrorDto(e.getHeaderName(), "header is required")), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied(AccessDeniedException e, HttpServletRequest r) {
        return respond(ErrorCode.FORBIDDEN, "Access denied", r, null, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> unauth(AuthenticationException e, HttpServletRequest r) {
        return respond(ErrorCode.UNAUTHENTICATED, "Authentication required", r, null, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> locking(ObjectOptimisticLockingFailureException e, HttpServletRequest r) {
        return respond(ErrorCode.TICKET_VERSION_CONFLICT,
                "The resource was modified by someone else; reload and retry", r, null, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException e, HttpServletRequest r) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return respond(ErrorCode.CONFLICT, "The request conflicts with existing data", r, null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e, HttpServletRequest r) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed", r, null, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest r) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", r, null, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e, HttpServletRequest r) {
        return respond(ErrorCode.NOT_FOUND, "Resource not found", r, null, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> other(Exception e, HttpServletRequest r) {
        log.error("Unhandled exception on {} {}", r.getMethod(), r.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_ERROR, "Unexpected error", r, null, null); // no internals leaked
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode c, String msg, HttpServletRequest r,
                                                  List<FieldErrorDto> f, String retryAfter) {
        var b = ResponseEntity.status(c.status());
        if (retryAfter != null) b.header(HttpHeaders.RETRY_AFTER, retryAfter);
        return b.body(ErrorWriter.build(c, msg, r.getRequestURI(), f));
    }
}
