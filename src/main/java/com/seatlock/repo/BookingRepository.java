package com.seatlock.repo;

import com.seatlock.domain.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    boolean existsByReference(String reference);

    @EntityGraph(attributePaths = {"seats", "event", "user"})
    Optional<Booking> findWithSeatsById(Long id);

    /** Fetches the seat rows alongside the page to keep the listing free of N+1 queries. */
    @EntityGraph(attributePaths = {"seats", "event"})
    @Query("SELECT b FROM Booking b WHERE b.user.id = :userId ORDER BY b.id DESC")
    Page<Booking> findByUser(@Param("userId") Long userId, Pageable pageable);

    long countByEventId(Long eventId);
}
