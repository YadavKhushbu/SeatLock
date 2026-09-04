package com.seatlock.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * The inventory row for one seat at one event, and the row every concurrent
 * booking attempt contends over.
 *
 * <p>Carries a JPA {@code @Version} column so that any update path which does
 * <em>not</em> take a pessimistic lock still fails loudly on a lost update
 * rather than silently overwriting a concurrent change.
 */
@Entity
@Table(name = "event_seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Version
    @Column(nullable = false)
    private Long version;
}
