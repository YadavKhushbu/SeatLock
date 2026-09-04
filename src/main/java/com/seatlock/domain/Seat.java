package com.seatlock.domain;

import jakarta.persistence.*;
import lombok.*;

/** A physical seat in a venue. Reused across every event held there. */
@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    @Column(nullable = false)
    private String section;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    /** Human-readable label such as {@code ORCHESTRA-C-14}, snapshotted onto bookings. */
    public String label() {
        return section + "-" + rowLabel + "-" + seatNumber;
    }
}
