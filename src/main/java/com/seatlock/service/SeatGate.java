package com.seatlock.service;

import com.seatlock.config.SeatLockProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Redis admission gate in front of the seat inventory.
 *
 * <h2>What this is, and what it is deliberately not</h2>
 *
 * <p>This is an <em>optimisation</em>, not the correctness mechanism. When ten
 * thousand people hit "buy" on the same seat, letting all ten thousand reach
 * Postgres means ten thousand transactions queueing on one row lock: the median
 * request waits behind the whole queue and the connection pool is exhausted long
 * before the seat is sold. The gate turns that into one winner who proceeds and
 * 9,999 losers rejected in well under a millisecond, without a database
 * connection ever being checked out.
 *
 * <p>It is explicitly <em>not</em> the thing that prevents double-selling. A
 * single-node Redis lock has no safety guarantee across failover: if the primary
 * dies after granting a lock but before replicating it, a promoted replica will
 * happily grant the same lock again. Systems that treat a Redis lock as the
 * source of truth oversell exactly when they are least able to afford it, during
 * an incident. Here, losing Redis entirely costs throughput and nothing else,
 * because every path behind this gate still takes a row lock and still answers to
 * the {@code ux_booking_seats_no_double_sell} unique index.
 *
 * @see com.seatlock.repo.EventSeatRepository#lockAllByIdInOrder for the layer that is authoritative
 */
@Component
public class SeatGate {

    private static final Logger log = LoggerFactory.getLogger(SeatGate.class);

    /**
     * Release is a compare-and-delete, never a bare DEL.
     *
     * <p>Consider: request A acquires the gate, stalls past the lease, the key
     * expires, request B acquires it, then A wakes up and releases. A bare DEL
     * would delete B's gate and admit a third request alongside B. Checking the
     * token first makes a stale owner's release a no-op. The check and the delete
     * are one Lua script so they cannot interleave.
     */
    private static final RedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SeatLockProperties props;

    public SeatGate(StringRedisTemplate redis, SeatLockProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Attempts to claim every seat, all or nothing.
     *
     * <p>Seat ids must already be in a consistent (ascending) order. Acquiring in
     * a fixed global order is what stops two overlapping multi-seat requests from
     * deadlocking each other: without it, one request holding seat 5 can wait on
     * seat 9 while the other holds 9 and waits on 5.
     *
     * @return true if all seats were claimed; on false nothing is left claimed
     */
    public boolean tryAcquireAll(Long eventId, List<Long> orderedSeatIds, String token) {
        List<Long> claimed = new ArrayList<>(orderedSeatIds.size());
        for (Long seatId : orderedSeatIds) {
            if (tryAcquire(eventId, seatId, token)) {
                claimed.add(seatId);
            } else {
                // Partial claims must not linger, or a failed request would keep
                // seats unbuyable until the lease expired.
                releaseAll(eventId, claimed, token);
                return false;
            }
        }
        return true;
    }

    private boolean tryAcquire(Long eventId, Long seatId, String token) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(key(eventId, seatId), token, props.lockLease());
        return Boolean.TRUE.equals(ok);
    }

    public void releaseAll(Long eventId, List<Long> seatIds, String token) {
        for (Long seatId : seatIds) {
            try {
                redis.execute(COMPARE_AND_DELETE, Collections.singletonList(key(eventId, seatId)), token);
            } catch (RuntimeException e) {
                // A failed release is survivable: the lease expires on its own.
                // Never let cleanup failure escape and mask the real outcome.
                log.warn("Could not release seat gate event={} seat={}: {}", eventId, seatId, e.toString());
            }
        }
    }

    /**
     * Drops the claim without checking ownership.
     *
     * <p>Only for callers that have already committed the authoritative change in
     * Postgres. Once the database says a seat is free, the gate is stale by
     * definition and whoever happens to hold the token is irrelevant.
     */
    public void forceReleaseAll(Long eventId, List<Long> seatIds) {
        for (Long seatId : seatIds) {
            try {
                redis.delete(key(eventId, seatId));
            } catch (RuntimeException e) {
                log.warn("Could not force-release seat gate event={} seat={}: {}", eventId, seatId, e.toString());
            }
        }
    }

    /**
     * Extends the claim to cover the life of a hold, so that repeat attempts on a
     * held seat are rejected at Redis instead of walking to the database.
     */
    public void extendAll(Long eventId, List<Long> seatIds, Duration ttl) {
        for (Long seatId : seatIds) {
            try {
                redis.expire(key(eventId, seatId), ttl);
            } catch (RuntimeException e) {
                log.warn("Could not extend seat gate event={} seat={}: {}", eventId, seatId, e.toString());
            }
        }
    }

    private String key(Long eventId, Long seatId) {
        return "seatlock:gate:" + eventId + ":" + seatId;
    }
}
