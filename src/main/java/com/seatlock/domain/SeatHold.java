package com.seatlock.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A time-boxed claim on one or more seats, created when a user picks seats and
 * consumed when they pay.
 *
 * <p>Holds are what make the checkout flow humane: without them a user would
 * lose their seats to a faster buyer while typing card details. They are also
 * the reason this system needs an expiry story at all, since an abandoned
 * checkout must return inventory to the pool without human intervention.
 */
@Entity
@Table(name = "seat_holds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatHold {

    /** Client-visible identifier. A UUID rather than a sequence so hold ids are not enumerable. */
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private HoldStatus status = HoldStatus.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "hold", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SeatHoldItem> items = new ArrayList<>();

    public boolean isLiveAt(Instant now) {
        return status == HoldStatus.ACTIVE && now.isBefore(expiresAt);
    }

    public void addSeat(EventSeat eventSeat) {
        SeatHoldItem item = SeatHoldItem.builder()
                .id(new SeatHoldItem.Key(id, eventSeat.getId()))
                .hold(this)
                .eventSeat(eventSeat)
                .build();
        items.add(item);
    }

    /**
     * Marks this hold and every one of its items as no longer live.
     *
     * <p>Stamping releasedAt on the items is what frees the seats under the
     * ux_hold_items_seat_live unique index, so it has to happen in the same
     * transaction as the status change or the seats stay stuck.
     */
    public void close(HoldStatus terminalStatus, Instant at) {
        this.status = terminalStatus;
        for (SeatHoldItem item : items) {
            if (item.getReleasedAt() == null) {
                item.setReleasedAt(at);
            }
        }
    }
}
