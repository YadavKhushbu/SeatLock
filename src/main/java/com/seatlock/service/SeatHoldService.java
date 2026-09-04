package com.seatlock.service;

import com.seatlock.config.SeatLockProperties;
import com.seatlock.domain.HoldStatus;
import com.seatlock.domain.SeatHold;
import com.seatlock.dto.Dtos;
import com.seatlock.exception.Exceptions;
import com.seatlock.repo.SeatHoldRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seat holds: the short-lived reservation a user gets while they check out.
 *
 * <p>Reserving a seat runs through two layers in order.
 *
 * <ol>
 *   <li>{@link SeatGate} — a Redis claim that rejects the overwhelming majority
 *       of contending requests in under a millisecond, so the database only ever
 *       sees plausible winners. Fast, and unsafe on its own.</li>
 *   <li>{@link SeatHoldTransactions#reserve} — row locks and unique constraints
 *       in Postgres. Slower, and correct even if layer one is bypassed entirely
 *       or Redis is down.</li>
 * </ol>
 *
 * <p>Deleting layer one would leave the system correct but slow. Deleting layer
 * two would leave it fast and wrong. They are not interchangeable, and the
 * ordering is deliberate.
 */
@Service
public class SeatHoldService {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldService.class);

    private final SeatGate gate;
    private final SeatHoldTransactions tx;
    private final SeatHoldRepository holds;
    private final SeatLockProperties props;

    private final Counter holdsGranted;
    private final Counter holdsRejectedAtGate;
    private final Counter holdsRejectedAtDatabase;
    private final Timer holdLatency;

    public SeatHoldService(SeatGate gate,
                           SeatHoldTransactions tx,
                           SeatHoldRepository holds,
                           SeatLockProperties props,
                           MeterRegistry metrics) {
        this.gate = gate;
        this.tx = tx;
        this.holds = holds;
        this.props = props;

        // Splitting rejections by layer is what makes the gate observable: if
        // database rejections start climbing relative to gate rejections, the
        // Redis fast path has stopped doing its job and the database is wearing
        // contention it should never have seen.
        this.holdsGranted = Counter.builder("seatlock.holds")
                .tag("outcome", "granted").register(metrics);
        this.holdsRejectedAtGate = Counter.builder("seatlock.holds")
                .tag("outcome", "rejected_at_gate").register(metrics);
        this.holdsRejectedAtDatabase = Counter.builder("seatlock.holds")
                .tag("outcome", "rejected_at_database").register(metrics);
        this.holdLatency = Timer.builder("seatlock.hold.duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(metrics);
    }

    public Dtos.HoldResponse createHold(Long eventId, Long userId, List<Long> requestedSeatIds) {
        Timer.Sample sample = Timer.start();
        try {
            return doCreateHold(eventId, userId, requestedSeatIds);
        } finally {
            sample.stop(holdLatency);
        }
    }

    private Dtos.HoldResponse doCreateHold(Long eventId, Long userId, List<Long> requestedSeatIds) {
        // Distinct and sorted. Sorting gives every request the same lock
        // acquisition order, which is what makes deadlock between two
        // overlapping multi-seat requests impossible rather than unlikely.
        List<Long> seatIds = requestedSeatIds.stream().distinct().sorted().toList();

        String token = UUID.randomUUID().toString();
        if (!gate.tryAcquireAll(eventId, seatIds, token)) {
            holdsRejectedAtGate.increment();
            throw new Exceptions.SeatUnavailable("One or more seats are being booked by someone else");
        }

        boolean granted = false;
        try {
            Instant now = Instant.now();
            Dtos.HoldResponse response = tx.reserve(eventId, userId, seatIds, props.holdTtl(), now);
            granted = true;

            // The claim now mirrors the hold's lifetime, so further attempts on
            // these seats are turned away at Redis rather than at a row lock.
            gate.extendAll(eventId, seatIds, props.holdTtl());

            holdsGranted.increment();
            return response;
        } catch (Exceptions.SeatUnavailable e) {
            holdsRejectedAtDatabase.increment();
            throw e;
        } finally {
            if (!granted) {
                // Nothing was reserved, so the claim must go back immediately;
                // otherwise a rejected request would make seats unbuyable for the
                // remainder of the lease.
                gate.releaseAll(eventId, seatIds, token);
            }
        }
    }

    /** Voluntary release, e.g. the user backed out of checkout. */
    public void releaseHold(UUID holdId, Long userId) {
        var closed = tx.close(holdId, userId, HoldStatus.RELEASED, Instant.now());
        if (!closed.seatIds().isEmpty()) {
            // Only after the database has committed the release do the seats
            // become claimable again. Dropping the gate first would let a new
            // request reach a row still marked HELD and be rejected for no
            // reason. The token is irrelevant now: Postgres is the authority on
            // who owns these seats, and it says nobody.
            gate.forceReleaseAll(closed.eventId(), closed.seatIds());
            log.debug("Released hold {} covering {} seats", holdId, closed.seatIds().size());
        }
    }

    @Transactional(readOnly = true)
    public Dtos.HoldResponse getHold(UUID holdId, Long userId) {
        SeatHold hold = holds.findWithItemsById(holdId)
                .orElseThrow(() -> new Exceptions.NotFound("Hold", holdId));
        if (!hold.getUser().getId().equals(userId)) {
            throw new Exceptions.NotFound("Hold", holdId);
        }
        return HoldMapper.toResponse(hold, Instant.now());
    }
}
