package com.seatlock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the booking flow, bound from the {@code seatlock.*} namespace.
 *
 * @param holdTtl        how long a seat hold survives before the reaper reclaims it
 * @param reaperBatch    maximum holds expired per reaper run, bounding transaction size
 * @param lockWait       how long a hold request waits on the Redis gate before giving up
 * @param lockLease      how long a Redis gate key lives if it is never released explicitly
 */
@ConfigurationProperties(prefix = "seatlock.booking")
public record SeatLockProperties(
        Duration holdTtl,
        int reaperBatch,
        Duration lockWait,
        Duration lockLease) {

    public SeatLockProperties {
        if (holdTtl == null) holdTtl = Duration.ofMinutes(8);
        if (reaperBatch <= 0) reaperBatch = 200;
        if (lockWait == null) lockWait = Duration.ofMillis(150);
        if (lockLease == null) lockLease = Duration.ofSeconds(10);
    }
}
