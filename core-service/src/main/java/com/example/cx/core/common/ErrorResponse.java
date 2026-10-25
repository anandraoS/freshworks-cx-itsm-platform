package com.example.cx.core.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Error schema from the API contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Instant timestamp, int status, String error, String code, String message,
                            String path, String correlationId, List<FieldErrorDto> fieldErrors) {}
