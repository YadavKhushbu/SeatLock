package com.seatlock.service;

import com.seatlock.domain.EventSeat;
import com.seatlock.domain.SeatHold;
import com.seatlock.domain.SeatHoldItem;
import com.seatlock.dto.Dtos;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Turns hold entities into wire responses.
 *
 * <p>Every method here must be called with the persistence context still open:
 * it walks lazy associations down to the seat. Building the response inside the
 * transaction that loaded the entities is what keeps a
 * {@code LazyInitializationException} from being possible at all, rather than a
 * thing to remember not to trigger.
 */
final class HoldMapper {

    private HoldMapper() {
    }

    static Dtos.HoldResponse toResponse(SeatHold hold, Instant now) {
        List<Dtos.SeatView> seats = hold.getItems().stream()
                .map(SeatHoldItem::getEventSeat)
                .sorted(Comparator.comparing(EventSeat::getId))
                .map(HoldMapper::toSeatView)
                .toList();

        long total = seats.stream().mapToLong(Dtos.SeatView::priceCents).sum();
        long secondsLeft = Math.max(0, Duration.between(now, hold.getExpiresAt()).toSeconds());

        return new Dtos.HoldResponse(
                hold.getId(),
                hold.getEvent().getId(),
                hold.getStatus().name(),
                hold.getExpiresAt(),
                secondsLeft,
                total,
                seats);
    }

    static Dtos.SeatView toSeatView(EventSeat es) {
        return new Dtos.SeatView(
                es.getId(),
                es.getSeat().getSection(),
                es.getSeat().getRowLabel(),
                es.getSeat().getSeatNumber(),
                es.getPriceCents(),
                es.getStatus().name());
    }
}
