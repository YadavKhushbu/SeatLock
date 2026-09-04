package com.seatlock;

import com.seatlock.domain.Event;
import com.seatlock.domain.EventStatus;
import com.seatlock.domain.HoldStatus;
import com.seatlock.domain.SeatHold;
import com.seatlock.domain.SeatHoldItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The time and status rules the booking flow depends on, tested without a database. */
@DisplayName("Hold and event state rules")
class SeatHoldDomainTest {

    private final Instant now = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    @DisplayName("A hold is live only while ACTIVE and before its expiry")
    void liveness() {
        SeatHold hold = SeatHold.builder()
                .id(UUID.randomUUID())
                .status(HoldStatus.ACTIVE)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES))
                .build();

        assertThat(hold.isLiveAt(now)).isTrue();
        assertThat(hold.isLiveAt(now.plus(6, ChronoUnit.MINUTES))).isFalse();

        // Exactly at expiry the hold is already gone. Ties go to the pool, not to
        // the holder, so a request arriving on the boundary can never convert a
        // hold the reaper is entitled to have taken.
        assertThat(hold.isLiveAt(now.plus(5, ChronoUnit.MINUTES))).isFalse();

        hold.setStatus(HoldStatus.RELEASED);
        assertThat(hold.isLiveAt(now)).isFalse();
    }

    @Test
    @DisplayName("Closing a hold stamps every item, which is what frees the seats")
    void closingStampsItems() {
        SeatHold hold = SeatHold.builder()
                .id(UUID.randomUUID())
                .status(HoldStatus.ACTIVE)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES))
                .items(new java.util.ArrayList<>(List.of(
                        SeatHoldItem.builder().build(),
                        SeatHoldItem.builder().build())))
                .build();

        hold.close(HoldStatus.CONVERTED, now);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONVERTED);
        assertThat(hold.getItems())
                .as("an unstamped item keeps its seat locked under the partial unique index")
                .allMatch(item -> now.equals(item.getReleasedAt()));
    }

    @Test
    @DisplayName("Closing an already-closed item does not rewrite its timestamp")
    void closingIsIdempotentPerItem() {
        Instant earlier = now.minus(1, ChronoUnit.MINUTES);
        SeatHoldItem alreadyReleased = SeatHoldItem.builder().releasedAt(earlier).build();

        SeatHold hold = SeatHold.builder()
                .id(UUID.randomUUID()).status(HoldStatus.ACTIVE).expiresAt(now)
                .items(new java.util.ArrayList<>(List.of(alreadyReleased)))
                .build();

        hold.close(HoldStatus.EXPIRED, now);

        assertThat(alreadyReleased.getReleasedAt())
                .as("the moment a seat was actually freed is the auditable fact; do not overwrite it")
                .isEqualTo(earlier);
    }

    @Test
    @DisplayName("An event is on sale only inside its sales window")
    void salesWindow() {
        Event event = Event.builder()
                .status(EventStatus.SCHEDULED)
                .salesOpenAt(now.minus(1, ChronoUnit.HOURS))
                .salesCloseAt(now.plus(1, ChronoUnit.HOURS))
                .startsAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        assertThat(event.isOnSaleAt(now)).isTrue();
        assertThat(event.isOnSaleAt(now.minus(2, ChronoUnit.HOURS))).isFalse();
        assertThat(event.isOnSaleAt(now.plus(2, ChronoUnit.HOURS))).isFalse();

        event.setStatus(EventStatus.CANCELLED);
        assertThat(event.isOnSaleAt(now))
                .as("a cancelled event must not sell tickets even mid-window")
                .isFalse();
    }
}
