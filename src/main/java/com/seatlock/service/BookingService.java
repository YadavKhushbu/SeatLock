package com.seatlock.service;

import com.seatlock.domain.*;
import com.seatlock.dto.Dtos;
import com.seatlock.exception.Exceptions;
import com.seatlock.repo.BookingRepository;
import com.seatlock.repo.EventSeatRepository;
import com.seatlock.repo.SeatHoldRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Turning a hold into a paid booking, and cancelling one afterwards.
 *
 * <p>Confirmation is the last point at which overselling is still possible, so
 * it repeats the same defence as the hold path rather than trusting that the
 * hold is still valid. A hold can expire between the check and the write; the
 * reaper may be closing it at this exact moment. The row locks taken here settle
 * that race, and the unique index catches anything the locks somehow missed.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    /** Excludes I, O, 0 and 1, which customers reliably mistype when reading a code aloud. */
    private static final char[] REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingRepository bookings;
    private final SeatHoldRepository holds;
    private final EventSeatRepository eventSeats;
    private final SeatGate gate;

    private final Counter confirmed;
    private final Counter conflicted;

    public BookingService(BookingRepository bookings,
                          SeatHoldRepository holds,
                          EventSeatRepository eventSeats,
                          SeatGate gate,
                          MeterRegistry metrics) {
        this.bookings = bookings;
        this.holds = holds;
        this.eventSeats = eventSeats;
        this.gate = gate;
        this.confirmed = Counter.builder("seatlock.bookings").tag("outcome", "confirmed").register(metrics);
        this.conflicted = Counter.builder("seatlock.bookings").tag("outcome", "conflict").register(metrics);
    }

    @Transactional
    public Dtos.BookingResponse confirm(UUID holdId, Long userId) {
        Instant now = Instant.now();

        SeatHold hold = holds.findWithItemsById(holdId)
                .orElseThrow(() -> new Exceptions.NotFound("Hold", holdId));

        if (!hold.getUser().getId().equals(userId)) {
            throw new Exceptions.NotFound("Hold", holdId);
        }
        if (hold.getStatus() == HoldStatus.CONVERTED) {
            // Almost always a retry without an Idempotency-Key. Say so precisely
            // rather than reporting a generic conflict the client cannot act on.
            throw new Exceptions.HoldNotLive("This hold has already been converted into a booking");
        }
        if (!hold.isLiveAt(now)) {
            conflicted.increment();
            throw new Exceptions.HoldNotLive("This hold expired at " + hold.getExpiresAt());
        }

        List<Long> seatIds = hold.getItems().stream()
                .map(item -> item.getEventSeat().getId())
                .sorted()
                .toList();

        // Same ordered pessimistic lock as the hold path. The check above read
        // the hold; this locks the inventory the hold points at, and only after
        // the lock is held is it safe to believe what the seats say.
        List<EventSeat> locked = eventSeats.lockAllByIdInOrder(seatIds);
        for (EventSeat seat : locked) {
            if (seat.getStatus() != SeatStatus.HELD) {
                conflicted.increment();
                throw new Exceptions.HoldNotLive(
                        "Seat " + seat.getId() + " is no longer held; the hold may have just expired");
            }
        }

        Booking booking = Booking.builder()
                .reference(nextReference())
                .event(hold.getEvent())
                .user(hold.getUser())
                .status(BookingStatus.CONFIRMED)
                .totalCents(locked.stream().mapToLong(EventSeat::getPriceCents).sum())
                .build();

        for (EventSeat seat : locked) {
            seat.setStatus(SeatStatus.BOOKED);
            booking.addSeat(seat, seat.getSeat().label());
        }
        hold.close(HoldStatus.CONVERTED, now);

        try {
            bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            conflicted.increment();
            // ux_booking_seats_no_double_sell rejected the insert. Reaching this
            // line means every layer above it was bypassed, so it is worth
            // logging loudly even though the customer outcome is a plain 409.
            log.warn("Double-sell prevented by unique index for seats {} on hold {}", seatIds, holdId);
            throw new Exceptions.SeatUnavailable("One or more seats were just sold to someone else");
        }

        Long eventId = hold.getEvent().getId();
        // Redis is only touched once the database has actually committed. Doing
        // it inline would drop the gate for a transaction that might still roll
        // back, briefly advertising sold seats as free.
        afterCommit(() -> gate.forceReleaseAll(eventId, seatIds));

        confirmed.increment();
        return toResponse(booking);
    }

    @Transactional
    public Dtos.BookingResponse cancel(Long bookingId, Long userId) {
        Booking booking = bookings.findWithSeatsById(bookingId)
                .orElseThrow(() -> new Exceptions.NotFound("Booking", bookingId));

        if (!booking.getUser().getId().equals(userId)) {
            throw new Exceptions.NotFound("Booking", bookingId);
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new Exceptions.BookingNotCancellable("This booking is already cancelled");
        }
        Instant now = Instant.now();
        if (!booking.getEvent().getStartsAt().isAfter(now)) {
            throw new Exceptions.BookingNotCancellable("The event has already started");
        }

        List<Long> seatIds = booking.getSeats().stream()
                .map(bs -> bs.getEventSeat().getId())
                .sorted()
                .toList();

        for (EventSeat seat : eventSeats.lockAllByIdInOrder(seatIds)) {
            seat.setStatus(SeatStatus.AVAILABLE);
        }
        // Stamping cancelled_at is what releases the seats under the partial
        // unique index, letting them be resold while the row survives for audit.
        booking.getSeats().forEach(bs -> bs.setCancelledAt(now));
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        bookings.save(booking);

        Long eventId = booking.getEvent().getId();
        afterCommit(() -> gate.forceReleaseAll(eventId, seatIds));

        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public Dtos.PageResponse<Dtos.BookingResponse> listForUser(Long userId, int page, int size) {
        Page<Booking> found = bookings.findByUser(userId, PageRequest.of(page, size));
        return new Dtos.PageResponse<>(
                found.getContent().stream().map(this::toResponse).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Dtos.BookingResponse getForUser(Long bookingId, Long userId) {
        Booking booking = bookings.findWithSeatsById(bookingId)
                .orElseThrow(() -> new Exceptions.NotFound("Booking", bookingId));
        if (!booking.getUser().getId().equals(userId)) {
            throw new Exceptions.NotFound("Booking", bookingId);
        }
        return toResponse(booking);
    }

    /**
     * Runs an action only if the surrounding transaction commits.
     *
     * <p>Falls back to running immediately when there is no active transaction,
     * so the method stays correct if it is ever called outside one.
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * Generates a booking reference, retrying on the vanishingly unlikely
     * collision. The unique constraint on the column is the real guard; this loop
     * just avoids surfacing a random 500 to a customer when it fires.
     */
    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder("SL-");
            for (int i = 0; i < 6; i++) {
                sb.append(REFERENCE_ALPHABET[RANDOM.nextInt(REFERENCE_ALPHABET.length)]);
            }
            String candidate = sb.toString();
            if (!bookings.existsByReference(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique booking reference");
    }

    private Dtos.BookingResponse toResponse(Booking booking) {
        List<Dtos.BookedSeat> seats = booking.getSeats().stream()
                .sorted(Comparator.comparing(BookingSeat::getSeatLabel))
                .map(bs -> new Dtos.BookedSeat(bs.getEventSeat().getId(), bs.getSeatLabel(), bs.getPriceCents()))
                .toList();

        return new Dtos.BookingResponse(
                booking.getId(),
                booking.getReference(),
                booking.getEvent().getId(),
                booking.getEvent().getTitle(),
                booking.getEvent().getStartsAt(),
                booking.getStatus().name(),
                booking.getTotalCents(),
                booking.getCreatedAt(),
                seats);
    }
}
