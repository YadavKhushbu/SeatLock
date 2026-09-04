package com.seatlock.service;

import com.seatlock.config.SeatLockProperties;
import com.seatlock.domain.HoldStatus;
import com.seatlock.repo.SeatHoldRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Returns abandoned holds to the inventory pool.
 *
 * <p>Most checkouts are never finished: a user picks seats, sees the price, and
 * closes the tab. Without this job those seats stay HELD forever and the event
 * sells out at thirty percent occupancy. Expiry is therefore not a cleanup nicety
 * but a core part of how inventory works.
 *
 * <h2>Why the lock</h2>
 *
 * <p>Every application instance runs this schedule. Without coordination, three
 * instances would each pull the same batch and race to expire the same holds, at
 * best wasting work and at worst deadlocking against each other on the seat rows.
 * ShedLock lets exactly one instance run each tick, and its lease expires on its
 * own if that instance dies mid-run.
 */
@Component
public class HoldReaper {

    private static final Logger log = LoggerFactory.getLogger(HoldReaper.class);

    private final SeatHoldRepository holds;
    private final SeatHoldTransactions tx;
    private final SeatGate gate;
    private final SeatLockProperties props;
    private final Counter expired;

    public HoldReaper(SeatHoldRepository holds,
                      SeatHoldTransactions tx,
                      SeatGate gate,
                      SeatLockProperties props,
                      MeterRegistry metrics) {
        this.holds = holds;
        this.tx = tx;
        this.gate = gate;
        this.props = props;
        this.expired = Counter.builder("seatlock.holds").tag("outcome", "expired").register(metrics);
    }

    @Scheduled(fixedDelayString = "${seatlock.booking.reaper-interval:PT10S}")
    @SchedulerLock(name = "seatlock-hold-reaper", lockAtLeastFor = "PT5S", lockAtMostFor = "PT2M")
    public void reclaimExpiredHolds() {
        Instant now = Instant.now();

        // Bounded batch. After an outage the backlog can be enormous, and one
        // transaction spanning every expired hold would lock a large slice of the
        // inventory table while live traffic is trying to use it.
        List<UUID> batch = holds.findExpiredHoldIds(
                HoldStatus.ACTIVE, now, PageRequest.of(0, props.reaperBatch()));

        if (batch.isEmpty()) {
            return;
        }

        int reclaimed = 0;
        for (UUID holdId : batch) {
            try {
                // Each hold commits independently, so one problematic row cannot
                // roll back the rest of the batch.
                var closed = tx.expireOne(holdId, now);
                if (!closed.seatIds().isEmpty()) {
                    gate.forceReleaseAll(closed.eventId(), closed.seatIds());
                    reclaimed++;
                }
            } catch (RuntimeException e) {
                // A hold that cannot be expired now will be picked up next tick.
                // Failing the whole run over one row would stall reclamation
                // entirely, which is far worse than one delayed hold.
                log.warn("Could not expire hold {}: {}", holdId, e.toString());
            }
        }

        expired.increment(reclaimed);
        log.info("Reaper reclaimed {} of {} expired holds", reclaimed, batch.size());
    }
}
