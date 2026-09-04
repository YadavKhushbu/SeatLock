package com.seatlock.service;

import com.seatlock.domain.*;
import com.seatlock.dto.Dtos;
import com.seatlock.exception.Exceptions;
import com.seatlock.repo.EventRepository;
import com.seatlock.repo.EventSeatRepository;
import com.seatlock.repo.SeatHoldRepository;
import com.seatlock.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The transactional half of the hold lifecycle.
 *
 * <p>Kept apart from {@link SeatHoldService} so the Redis round-trips in that
 * class happen strictly outside a database transaction. Waiting on a network
 * call with an open transaction pins a pooled connection for the duration, and
 * under load that is how a service runs out of connections while doing almost no
 * work.
 */
@Service
public class SeatHoldTransactions {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldTransactions.class);

    private final EventRepository events;
    private final EventSeatRepository eventSeats;
    private final SeatHoldRepository holds;
    private final UserRepository users;

    public SeatHoldTransactions(EventRepository events,
                                EventSeatRepository eventSeats,
                                SeatHoldRepository holds,
                                UserRepository users) {
        this.events = events;
        this.eventSeats = eventSeats;
        this.holds = holds;
        this.users = users;
    }

    /**
     * Reserves the given seats for a user, or fails without side effects.
     *
     * <p>The ordering of operations here is the whole point:
     * <ol>
     *   <li>Lock the seat rows first, in id order, before reading their status.
     *       Reading and then locking would let another transaction change the
     *       status in between, which is precisely the race being defended
     *       against.</li>
     *   <li>Re-check availability <em>after</em> acquiring the lock. Whatever the
     *       caller believed when it rendered a seat map is stale by now.</li>
     *   <li>Let the unique index have the final word. If two transactions somehow
     *       both got this far, one commit fails and is reported as a normal
     *       conflict.</li>
     * </ol>
     */
    @Transactional
    public Dtos.HoldResponse reserve(Long eventId, Long userId, List<Long> orderedSeatIds, Duration ttl, Instant now) {
        Event event = events.findWithVenueById(eventId)
                .orElseThrow(() -> new Exceptions.NotFound("Event", eventId));
        if (!event.isOnSaleAt(now)) {
            throw new Exceptions.EventNotOnSale(eventId);
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new Exceptions.NotFound("User", userId));

        List<EventSeat> locked = eventSeats.lockAllByIdInOrder(orderedSeatIds);

        if (locked.size() != orderedSeatIds.size()) {
            throw new Exceptions.NotFound("One or more seats", orderedSeatIds);
        }
        for (EventSeat seat : locked) {
            if (!seat.getEvent().getId().equals(eventId)) {
                throw new Exceptions.SeatUnavailable(
                        "Seat " + seat.getId() + " does not belong to event " + eventId);
            }
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new Exceptions.SeatUnavailable(
                        "Seat " + seat.getId() + " is already " + seat.getStatus().name().toLowerCase());
            }
        }

        SeatHold hold = SeatHold.builder()
                .id(UUID.randomUUID())
                .event(event)
                .user(user)
                .status(HoldStatus.ACTIVE)
                .expiresAt(now.plus(ttl))
                .build();

        for (EventSeat seat : locked) {
            seat.setStatus(SeatStatus.HELD);
            hold.addSeat(seat);
        }

        try {
            holds.saveAndFlush(hold);
        } catch (DataIntegrityViolationException e) {
            // ux_hold_items_seat_live fired. Two requests raced past every check
            // above and the database settled it. Report it as the ordinary
            // conflict it is.
            log.debug("Hold lost the race at the unique index for seats {}", orderedSeatIds);
            throw new Exceptions.SeatUnavailable("One or more seats were taken while you were choosing");
        }
        // Mapped here, inside the transaction, so the lazy walk down to seat
        // labels resolves against an open persistence context.
        return HoldMapper.toResponse(hold, now);
    }

    /**
     * What a close freed, so the caller can clear the matching Redis gates
     * without needing to reload the hold outside the transaction.
     *
     * @param seatIds empty when the hold was already closed, making the whole
     *                operation naturally idempotent
     */
    public record ClosedHold(Long eventId, List<Long> seatIds) {
    }

    /**
     * Ends a live hold and returns its seats to the pool.
     *
     * @param expectedUserId when non-null, the hold must belong to this user
     */
    @Transactional
    public ClosedHold close(UUID holdId, Long expectedUserId, HoldStatus terminalStatus, Instant now) {
        SeatHold hold = holds.findWithItemsById(holdId)
                .orElseThrow(() -> new Exceptions.NotFound("Hold", holdId));

        if (expectedUserId != null && !hold.getUser().getId().equals(expectedUserId)) {
            // Deliberately the same 404 an unknown id produces: telling a caller
            // that a hold exists but belongs to someone else leaks information.
            throw new Exceptions.NotFound("Hold", holdId);
        }
        Long eventId = hold.getEvent().getId();
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            // Already released, converted or reaped. Closing twice is a no-op
            // rather than an error: the reaper and a user clicking "cancel" can
            // legitimately race, and neither should see a failure.
            return new ClosedHold(eventId, List.of());
        }

        List<Long> seatIds = hold.getItems().stream()
                .map(item -> item.getEventSeat().getId())
                .sorted()
                .toList();

        // Lock before mutating, for the same reason as in reserve: a booking may
        // be converting this very hold right now.
        for (EventSeat seat : eventSeats.lockAllByIdInOrder(seatIds)) {
            if (seat.getStatus() == SeatStatus.HELD) {
                seat.setStatus(SeatStatus.AVAILABLE);
            }
        }
        hold.close(terminalStatus, now);
        holds.save(hold);
        return new ClosedHold(eventId, seatIds);
    }

    /**
     * Expires one hold in its own transaction.
     *
     * <p>{@code REQUIRES_NEW} so that the reaper processes a batch as N
     * independent units: one hold that cannot be locked must not roll back the
     * other 199 alongside it.
     *
     * <p>The call to {@link #close} below is a self-invocation and so bypasses
     * the proxy, meaning that method's own annotation does not apply. That is
     * harmless here — the transaction this method opened is already active and
     * {@code close} would have joined it regardless — but it is only harmless
     * because {@code close} declares the default {@code REQUIRED}. Changing its
     * propagation would silently have no effect on this path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClosedHold expireOne(UUID holdId, Instant now) {
        return close(holdId, null, HoldStatus.EXPIRED, now);
    }
}
