package com.seatlock.web;

import com.seatlock.dto.Dtos;
import com.seatlock.security.AuthUser;
import com.seatlock.service.BookingService;
import com.seatlock.service.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings")
@Validated
public class BookingController {

    /** Signals to the client that this response was replayed, not freshly computed. */
    private static final String REPLAY_HEADER = "Idempotent-Replay";

    private final BookingService bookingService;
    private final IdempotencyService idempotency;

    public BookingController(BookingService bookingService, IdempotencyService idempotency) {
        this.bookingService = bookingService;
        this.idempotency = idempotency;
    }

    @PostMapping
    @Operation(summary = "Convert a hold into a confirmed booking",
            description = "Send an Idempotency-Key header so that retrying after a lost response "
                    + "replays the original booking instead of creating a second one.")
    public ResponseEntity<Dtos.BookingResponse> create(
            @Parameter(description = "Client-generated unique key, e.g. a UUID per checkout attempt")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody Dtos.CreateBookingRequest request,
            @AuthenticationPrincipal AuthUser user) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Allowed, but the caller owns the consequences of a retry. The hold
            // itself gives partial protection: a converted hold cannot convert
            // twice, so the realistic worst case is a confusing 409 rather than a
            // duplicate purchase.
            return created(bookingService.confirm(request.holdId(), user.id()));
        }

        Optional<IdempotencyService.StoredResponse> replay =
                idempotency.claim(idempotencyKey, user.id(), request);

        if (replay.isPresent()) {
            IdempotencyService.StoredResponse stored = replay.get();
            return ResponseEntity.status(stored.status())
                    .header(REPLAY_HEADER, "true")
                    .body(idempotency.readJson(stored.body(), Dtos.BookingResponse.class));
        }

        try {
            Dtos.BookingResponse booking = bookingService.confirm(request.holdId(), user.id());
            idempotency.complete(idempotencyKey, user.id(), HttpStatus.CREATED.value(), booking);
            return created(booking);
        } catch (RuntimeException e) {
            // Release the claim so the same key can be retried. Leaving it in
            // place would strand the client: every retry would be told an
            // identical request is in flight, forever.
            idempotency.abandon(idempotencyKey, user.id());
            throw e;
        }
    }

    @GetMapping
    @Operation(summary = "List the caller's bookings")
    public Dtos.PageResponse<Dtos.BookingResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AuthUser user) {
        return bookingService.listForUser(user.id(), page, size);
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Fetch one of the caller's bookings")
    public Dtos.BookingResponse get(@PathVariable Long bookingId, @AuthenticationPrincipal AuthUser user) {
        return bookingService.getForUser(bookingId, user.id());
    }

    @PostMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking and return its seats to sale")
    public Dtos.BookingResponse cancel(@PathVariable Long bookingId, @AuthenticationPrincipal AuthUser user) {
        return bookingService.cancel(bookingId, user.id());
    }

    private ResponseEntity<Dtos.BookingResponse> created(Dtos.BookingResponse booking) {
        return ResponseEntity.status(HttpStatus.CREATED).body(booking);
    }
}
