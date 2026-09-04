package com.seatlock.domain;

public enum HoldStatus {
    /** Live: seats are reserved for this user until {@code expiresAt}. */
    ACTIVE,
    /** Successfully turned into a booking. */
    CONVERTED,
    /** Given up by the user before expiry. */
    RELEASED,
    /** Timed out and reclaimed by the reaper. */
    EXPIRED
}
