package com.seatlock.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for every error this API raises deliberately.
 *
 * <p>Carrying the status and a stable machine-readable code on the exception
 * keeps the handler in {@code GlobalExceptionHandler} a single generic branch,
 * rather than a growing list of per-exception mappings that drift out of sync.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
