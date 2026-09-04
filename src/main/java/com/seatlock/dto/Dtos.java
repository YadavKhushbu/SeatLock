package com.seatlock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response payloads.
 *
 * <p>All records: immutable, no boilerplate, and structurally impossible to
 * half-populate. Entities are never returned from a controller, so a lazy
 * association can never be serialised by accident and the wire format is free
 * to evolve separately from the schema.
 */
public final class Dtos {

    private Dtos() {
    }

    // ---------------------------------------------------------------- auth

    public record RegisterRequest(
            @Email(message = "must be a valid email address")
            @NotBlank String email,

            @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
            @NotBlank String password,

            @NotBlank @Size(max = 255) String fullName) {
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    @Schema(description = "A signed JWT and the seconds until it expires")
    public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds, UserSummary user) {
        public static AuthResponse bearer(String token, long ttlSeconds, UserSummary user) {
            return new AuthResponse(token, "Bearer", ttlSeconds, user);
        }
    }

    public record UserSummary(Long id, String email, String fullName) {
    }

    // --------------------------------------------------------------- events

    public record EventSummary(
            Long id,
            String title,
            String venueName,
            String city,
            Instant startsAt,
            String status,
            long availableSeats) {
    }

    public record EventDetail(
            Long id,
            String title,
            String description,
            String venueName,
            String city,
            Instant startsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            String status,
            long totalSeats,
            long availableSeats) {
    }

    public record SeatView(
            Long eventSeatId,
            String section,
            String rowLabel,
            int seatNumber,
            long priceCents,
            String status) {
    }

    public record SeatMap(Long eventId, int total, long available, List<SeatView> seats) {
    }

    // ---------------------------------------------------------------- holds

    public record CreateHoldRequest(
            @NotEmpty(message = "pick at least one seat")
            @Size(max = 10, message = "at most 10 seats per hold")
            List<@NotNull Long> eventSeatIds) {
    }

    public record HoldResponse(
            UUID holdId,
            Long eventId,
            String status,
            Instant expiresAt,
            long secondsRemaining,
            long totalCents,
            List<SeatView> seats) {
    }

    // -------------------------------------------------------------- bookings

    public record CreateBookingRequest(@NotNull UUID holdId) {
    }

    public record BookingResponse(
            Long id,
            String reference,
            Long eventId,
            String eventTitle,
            Instant eventStartsAt,
            String status,
            long totalCents,
            Instant createdAt,
            List<BookedSeat> seats) {
    }

    public record BookedSeat(Long eventSeatId, String seatLabel, long priceCents) {
    }

    // ---------------------------------------------------------------- common

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(
            Instant timestamp,
            int status,
            String code,
            String message,
            String path,
            List<FieldViolation> violations) {
    }

    public record FieldViolation(String field, String message) {
    }
}
