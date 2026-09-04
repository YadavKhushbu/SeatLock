package com.seatlock.service;

import com.seatlock.domain.Event;
import com.seatlock.domain.EventSeat;
import com.seatlock.domain.SeatStatus;
import com.seatlock.dto.Dtos;
import com.seatlock.exception.Exceptions;
import com.seatlock.repo.EventRepository;
import com.seatlock.repo.EventSeatRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class EventService {

    private final EventRepository events;
    private final EventSeatRepository eventSeats;

    public EventService(EventRepository events, EventSeatRepository eventSeats) {
        this.events = events;
        this.eventSeats = eventSeats;
    }

    @Transactional(readOnly = true)
    public Dtos.PageResponse<Dtos.EventSummary> listUpcoming(String city, int page, int size) {
        Instant now = Instant.now();
        PageRequest pageRequest = PageRequest.of(page, size);

        String normalisedCity = (city == null || city.isBlank()) ? null : city.trim().toLowerCase();
        Page<Event> found = normalisedCity == null
                ? events.findUpcoming(now, pageRequest)
                : events.findUpcomingInCity(now, normalisedCity, pageRequest);

        // One grouped count for the whole page rather than one per event.
        Map<Long, Long> availability = availabilityFor(
                found.getContent().stream().map(Event::getId).toList());

        List<Dtos.EventSummary> content = found.getContent().stream()
                .map(e -> new Dtos.EventSummary(
                        e.getId(),
                        e.getTitle(),
                        e.getVenue().getName(),
                        e.getVenue().getCity(),
                        e.getStartsAt(),
                        e.getStatus().name(),
                        availability.getOrDefault(e.getId(), 0L)))
                .toList();

        return new Dtos.PageResponse<>(content, found.getNumber(), found.getSize(),
                found.getTotalElements(), found.getTotalPages());
    }

    /**
     * @return available-seat count per event; an event with none sold-out returns
     *         no row from the group-by, so callers must default it to zero
     */
    private Map<Long, Long> availabilityFor(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        return eventSeats.countByEventIdsAndStatus(eventIds, SeatStatus.AVAILABLE).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]));
    }

    @Transactional(readOnly = true)
    public Dtos.EventDetail get(Long eventId) {
        Event event = events.findWithVenueById(eventId)
                .orElseThrow(() -> new Exceptions.NotFound("Event", eventId));

        // Counted in the database. Loading every seat row to call size() on the
        // list would pull an entire venue into memory to produce one number.
        long total = eventSeats.countByEventId(eventId);
        long available = eventSeats.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);

        return new Dtos.EventDetail(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getVenue().getName(),
                event.getVenue().getCity(),
                event.getStartsAt(),
                event.getSalesOpenAt(),
                event.getSalesCloseAt(),
                event.getStatus().name(),
                total,
                available);
    }

    /**
     * The seat map a client renders for seat selection.
     *
     * <p>Explicitly a snapshot, not a promise. By the time a user has read it and
     * clicked, any of these seats may be gone, which is exactly why the hold
     * endpoint re-checks everything under a lock instead of trusting what the
     * client believed it saw.
     */
    @Transactional(readOnly = true)
    public Dtos.SeatMap seatMap(Long eventId) {
        if (!events.existsById(eventId)) {
            throw new Exceptions.NotFound("Event", eventId);
        }
        List<EventSeat> seats = eventSeats.findSeatMap(eventId);

        List<Dtos.SeatView> views = seats.stream().map(HoldMapper::toSeatView).toList();
        long available = views.stream().filter(v -> SeatStatus.AVAILABLE.name().equals(v.status())).count();

        return new Dtos.SeatMap(eventId, views.size(), available, views);
    }
}
