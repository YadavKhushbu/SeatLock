package com.seatlock.web;

import com.seatlock.dto.Dtos;
import com.seatlock.security.AuthUser;
import com.seatlock.service.SeatHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Seat holds")
public class HoldController {

    private final SeatHoldService holdService;

    public HoldController(SeatHoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping("/events/{eventId}/holds")
    @Operation(summary = "Reserve seats for a few minutes",
            description = "Returns 409 SEAT_UNAVAILABLE if any requested seat is taken. "
                    + "The hold expires at the returned timestamp unless converted into a booking.")
    public ResponseEntity<Dtos.HoldResponse> create(@PathVariable Long eventId,
                                                    @Valid @RequestBody Dtos.CreateHoldRequest request,
                                                    @AuthenticationPrincipal AuthUser user) {
        Dtos.HoldResponse hold = holdService.createHold(eventId, user.id(), request.eventSeatIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(hold);
    }

    @GetMapping("/holds/{holdId}")
    @Operation(summary = "Check a hold and how long is left on it")
    public Dtos.HoldResponse get(@PathVariable UUID holdId, @AuthenticationPrincipal AuthUser user) {
        return holdService.getHold(holdId, user.id());
    }

    @DeleteMapping("/holds/{holdId}")
    @Operation(summary = "Give up a hold and return the seats immediately",
            description = "Safe to call more than once; releasing an already-closed hold is a no-op.")
    public ResponseEntity<Void> release(@PathVariable UUID holdId, @AuthenticationPrincipal AuthUser user) {
        holdService.releaseHold(holdId, user.id());
        return ResponseEntity.noContent().build();
    }
}
