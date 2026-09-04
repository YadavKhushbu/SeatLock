package com.seatlock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Join row between a hold and a seat.
 *
 * <p>The releasedAt column is deliberately duplicated from the parent hold
 * status: a partial unique index on (event_seat_id) WHERE released_at IS NULL is
 * what physically prevents two live holds on one seat, and Postgres will not
 * accept a subquery inside an index predicate.
 */
@Entity
@Table(name = "seat_hold_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatHoldItem {

    @EmbeddedId
    private Key id;

    @MapsId("holdId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hold_id")
    private SeatHold hold;

    @MapsId("eventSeatId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_seat_id")
    private EventSeat eventSeat;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Column(name = "hold_id")
        private UUID holdId;

        @Column(name = "event_seat_id")
        private Long eventSeatId;
    }
}
