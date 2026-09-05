package com.seatlock.web;

import com.seatlock.dto.Dtos;
import com.seatlock.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

/**
 * Renders every failure in one envelope, so clients parse one shape.
 *
 * <p>Each handler decides deliberately between 4xx and 5xx. Contention is not a
 * server fault: a lost race for a seat is the system working correctly, and
 * reporting it as 500 would both mislead the client and bury real defects under
 * noise in the error rate.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Dtos.ApiError> handleApi(ApiException e, HttpServletRequest request) {
        return build(e.getStatus(), e.getCode(), e.getMessage(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dtos.ApiError> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        List<Dtos.FieldViolation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new Dtos.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request body failed validation", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Dtos.ApiError> handleConstraint(ConstraintViolationException e,
                                                          HttpServletRequest request) {
        List<Dtos.FieldViolation> violations = e.getConstraintViolations().stream()
                .map(v -> new Dtos.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request parameters failed validation", request, violations);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Dtos.ApiError> handleMalformed(Exception e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request could not be parsed", request, null);
    }

    /**
     * Lock acquisition failures under load.
     *
     * <p>Returned as 409 with a Retry-After hint rather than 500: nothing is
     * broken, the row was simply busy, and the correct client behaviour is to try
     * again in a moment.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class,
            CannotAcquireLockException.class,
            OptimisticLockingFailureException.class})
    public ResponseEntity<Dtos.ApiError> handleLockContention(Exception e, HttpServletRequest request) {
        log.debug("Lock contention on {}: {}", request.getRequestURI(), e.toString());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(new Dtos.ApiError(Instant.now(), HttpStatus.CONFLICT.value(), "SEATS_BUSY",
                        "Those seats are being booked right now; please retry",
                        request.getRequestURI(), null));
    }

    /**
     * A request for something that is not there.
     *
     * <p>Without this, Spring's {@code NoResourceFoundException} falls through to
     * the catch-all below and becomes a 500 with an ERROR log line. Browsers ask
     * every site for {@code /favicon.ico}, so that single omission turns an
     * ordinary page visit into a logged server error and quietly inflates the
     * error rate that on-call alerting watches.
     *
     * <p>Logged at debug: a 404 is information for the caller, not an incident.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Dtos.ApiError> handleMissingResource(NoResourceFoundException e,
                                                               HttpServletRequest request) {
        log.debug("No resource at {}", request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No resource at this path", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        // The only branch that logs at error level, and the only one that should:
        // everything above is an anticipated outcome with a defined response.
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong on our side", request, null);
    }

    private ResponseEntity<Dtos.ApiError> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request,
                                                List<Dtos.FieldViolation> violations) {
        return ResponseEntity.status(status).body(new Dtos.ApiError(
                Instant.now(), status.value(), code, message, request.getRequestURI(), violations));
    }
}
