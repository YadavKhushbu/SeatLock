package com.seatlock.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One seat inside a booking.
 *
 * <p>Rows are soft-cancelled rather than deleted so a cancelled booking stays
 * auditable, while the partial unique index on (event_seat_id) WHERE
 * cancelled_at IS NULL still lets the seat be resold.
 */
@Entity
@Table(name = "booking_seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_seat_id")
    private EventSeat eventSeat;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    /** Seat label snapshotted at purchase time, so renumbering a venue cannot rewrite history. */
    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
