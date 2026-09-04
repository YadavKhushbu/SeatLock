package com.seatlock.repo;

import com.seatlock.domain.HoldStatus;
import com.seatlock.domain.SeatHold;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    /**
     * Loads a hold with everything a response needs in one query.
     *
     * <p>{@code items.eventSeat.seat} is included because the seat label lives
     * two hops down; omitting it turns a single fetch into one extra query per
     * seat on a path that runs on every checkout.
     */
    @EntityGraph(attributePaths = {"items", "items.eventSeat", "items.eventSeat.seat", "event", "user"})
    Optional<SeatHold> findWithItemsById(UUID id);

    /**
     * Batch of holds whose time is up, oldest first.
     *
     * <p>Paged rather than unbounded: after an outage the backlog can be large,
     * and a single transaction over every expired hold would hold locks across
     * the entire inventory table.
     */
    @Query("""
            SELECT h.id FROM SeatHold h
            WHERE h.status = :status AND h.expiresAt < :now
            ORDER BY h.expiresAt ASC
            """)
    List<UUID> findExpiredHoldIds(@Param("status") HoldStatus status,
                                  @Param("now") Instant now,
                                  Pageable pageable);
}
