package com.seatlock.exception;

import org.springframework.http.HttpStatus;

/**
 * The concrete API errors, grouped in one file because each is a two-line
 * subclass and scattering them across eight files buys nothing.
 */
public final class Exceptions {

    private Exceptions() {
    }

    public static class NotFound extends ApiException {
        public NotFound(String what, Object id) {
            super(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " was not found");
        }
    }

    public static class EmailTaken extends ApiException {
        public EmailTaken(String email) {
            super(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account already exists for " + email);
        }
    }

    public static class BadCredentials extends ApiException {
        public BadCredentials() {
            super(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Email or password is incorrect");
        }
    }

    public static class EventNotOnSale extends ApiException {
        public EventNotOnSale(Long eventId) {
            super(HttpStatus.CONFLICT, "EVENT_NOT_ON_SALE", "Event " + eventId + " is not currently on sale");
        }
    }

    /**
     * Raised when at least one requested seat was taken by someone else. This is
     * the expected outcome for all-but-one of a burst of concurrent requests, so
     * it is a normal 409 rather than an error condition worth alerting on.
     */
    public static class SeatUnavailable extends ApiException {
        public SeatUnavailable(String detail) {
            super(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE", detail);
        }
    }

    public static class HoldNotLive extends ApiException {
        public HoldNotLive(String detail) {
            super(HttpStatus.CONFLICT, "HOLD_NOT_LIVE", detail);
        }
    }

    public static class Forbidden extends ApiException {
        public Forbidden(String detail) {
            super(HttpStatus.FORBIDDEN, "FORBIDDEN", detail);
        }
    }

    /** Same Idempotency-Key replayed with a different body. */
    public static class IdempotencyKeyReused extends ApiException {
        public IdempotencyKeyReused() {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request body");
        }
    }

    /** A retry arrived while the original request is still running. */
    public static class RequestInFlight extends ApiException {
        public RequestInFlight() {
            super(HttpStatus.CONFLICT, "REQUEST_IN_FLIGHT",
                    "An identical request is already in progress; retry shortly");
        }
    }

    public static class BookingNotCancellable extends ApiException {
        public BookingNotCancellable(String detail) {
            super(HttpStatus.CONFLICT, "BOOKING_NOT_CANCELLABLE", detail);
        }
    }
}
