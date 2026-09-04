package com.seatlock.web;

import com.seatlock.dto.Dtos;
import com.seatlock.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events")
@SecurityRequirements
@Validated
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    @Operation(summary = "List upcoming events, optionally filtered by city")
    public Dtos.PageResponse<Dtos.EventSummary> list(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return eventService.listUpcoming(city, page, size);
    }

    @GetMapping("/{eventId}")
    @Operation(summary = "Event detail with live availability")
    public Dtos.EventDetail get(@PathVariable Long eventId) {
        return eventService.get(eventId);
    }

    @GetMapping("/{eventId}/seats")
    @Operation(summary = "Seat map for an event",
            description = "A point-in-time snapshot. Seats shown as AVAILABLE may be taken "
                    + "by the time a hold is requested; the hold endpoint re-checks under a lock.")
    public Dtos.SeatMap seatMap(@PathVariable Long eventId) {
        return eventService.seatMap(eventId);
    }
}
