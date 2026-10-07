package com.aegistrace.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(error(ex.getCode(), ex.getMessage(), ex.getRunId() == null ? null : ex.getRunId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        var message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("Request validation failed.");
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", message, null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> data(DataAccessException ex) {
        log.error("database_error");
        return ResponseEntity.status(503).body(error("DATABASE_FAILURE", "The database is unavailable.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> fallback(Exception ex, HttpServletRequest request) {
        log.error("unhandled_error path={}", request.getRequestURI(), ex);
        return ResponseEntity.internalServerError().body(error("INTERNAL_ERROR", "The request could not be completed.", null));
    }

    private ApiError error(String code, String message, java.util.UUID runId) {
        return new ApiError(code, message, MDC.get("trace_id"), runId, Instant.now());
    }
}
