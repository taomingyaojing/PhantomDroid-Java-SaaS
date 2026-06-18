package com.phantomdroid.exception;

import com.phantomdroid.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Global exception handler for both synchronous and asynchronous paths.
 * Covers 401 Unauthorized, 403 Forbidden, validation errors, and generic failures.
 */
@RestControllerAdvice
public class GlobalAsyncExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalAsyncExceptionHandler.class);

    /**
     * 400 — Validation errors from @Valid annotations.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());
        log.warn("Validation error: {}", errors);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "Validation failed", errors));
    }

    /**
     * 403 — Forbidden cross-user access (SecurityException from ownership checks).
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(SecurityException ex) {
        log.warn("Forbidden access: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(403, "Forbidden: " + ex.getMessage()));
    }

    /**
     * 401 — Not logged in / token expired (IllegalStateException from missing UserContext).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(IllegalStateException ex) {
        log.warn("Unauthorized: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(401, "Unauthorized: " + ex.getMessage()));
    }

    /**
     * 500 — Async task failures (CompletionException wrappers).
     */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<ApiResponse<Void>> handleAsyncException(CompletionException ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        // Unwrap SecurityException from async paths
        if (cause instanceof SecurityException) {
            log.warn("Forbidden (async): {}", cause.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "Forbidden: " + cause.getMessage()));
        }

        log.error("Async task failed: {}", cause.getMessage(), cause);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "Async operation failed: " + cause.getMessage()));
    }

    /**
     * 400 — Bad arguments.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadArgument(IllegalArgumentException ex) {
        log.warn("Bad argument: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, ex.getMessage()));
    }

    /**
     * 500 — Catch-all for unhandled exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "Internal server error: " + ex.getMessage()));
    }
}
