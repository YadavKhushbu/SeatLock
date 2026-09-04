package com.seatlock.repo;

import com.seatlock.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    @EntityGraph(attributePaths = {"venue"})
    Optional<Event> findWithVenueById(Long id);

    /**
     * Upcoming events, unfiltered.
     *
     * <p>Deliberately a separate method from {@link #findUpcomingInCity} rather
     * than one query with an {@code (:city IS NULL OR ...)} clause. That pattern
     * fails on Postgres: with a null argument Hibernate cannot infer the
     * parameter type, binds it as {@code bytea}, and the database rejects the
     * statement with {@code function lower(bytea) does not exist}. It is fixable
     * with an explicit cast, but two plain queries are clearer, and each gives
     * the planner a predicate it can actually use an index for.
     */
    @EntityGraph(attributePaths = {"venue"})
    @Query("SELECT e FROM Event e WHERE e.startsAt >= :from ORDER BY e.startsAt ASC")
    Page<Event> findUpcoming(@Param("from") Instant from, Pageable pageable);

    /** @param city must already be lower-cased and trimmed by the caller */
    @EntityGraph(attributePaths = {"venue"})
    @Query("""
            SELECT e FROM Event e
            WHERE e.startsAt >= :from
              AND LOWER(e.venue.city) = :city
            ORDER BY e.startsAt ASC
            """)
    Page<Event> findUpcomingInCity(@Param("from") Instant from,
                                   @Param("city") String city,
                                   Pageable pageable);
}
