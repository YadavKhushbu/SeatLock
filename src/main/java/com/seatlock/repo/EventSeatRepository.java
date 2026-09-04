package com.seatlock.repo;

import com.seatlock.domain.EventSeat;
import com.seatlock.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    List<EventSeat> findByEventIdOrderById(Long eventId);

    long countByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    /**
     * Availability for a whole page of events in one query.
     *
     * <p>Counting per event inside a loop turns a 20-event listing into 21
     * queries. This is the single most common way a correct-looking Spring Data
     * listing endpoint becomes the slowest thing in a service.
     *
     * @return rows of {@code [eventId, count]}
     */
    @Query("""
            SELECT es.event.id, COUNT(es)
            FROM EventSeat es
            WHERE es.event.id IN :eventIds AND es.status = :status
            GROUP BY es.event.id
            """)
    List<Object[]> countByEventIdsAndStatus(@Param("eventIds") Collection<Long> eventIds,
                                            @Param("status") SeatStatus status);

    /**
     * Loads the given seats under a row-level {@code SELECT ... FOR UPDATE}.
     *
     * <p>This is the serialisation point of the whole application. Two requests
     * contending for the same seat both reach this line; one acquires the row
     * lock and the other blocks here until the first commits, at which point it
     * re-reads the row and sees status HELD or BOOKED.
     *
     * <p>Two details matter as much as the lock itself:
     * <ul>
     *   <li>{@code ORDER BY id} — every caller must take locks in the same order
     *       or two multi-seat requests grabbing overlapping seats can deadlock
     *       (A locks 5 then waits on 9; B locks 9 then waits on 5). Sorting by a
     *       total order makes that cycle impossible.</li>
     *   <li>A bounded wait — without one, a stuck transaction parks every other
     *       request on this row until the connection pool drains. Failing fast
     *       turns a site-wide outage into one retryable 409.</li>
     * </ul>
     *
     * <p>Note on the timeout hint below: PostgreSQL has no {@code FOR UPDATE WAIT n}
     * syntax, so Hibernate cannot honour a non-zero lock timeout there and the
     * hint is effectively ignored. The real bound comes from
     * {@code SET lock_timeout} applied to every pooled connection (see
     * {@code connection-init-sql} in application.yml). The hint is kept because
     * it is honoured on databases that do support it, but it is not what is
     * protecting this query in production.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT es FROM EventSeat es WHERE es.id IN :ids ORDER BY es.id")
    List<EventSeat> lockAllByIdInOrder(@Param("ids") Collection<Long> ids);

    @Query("""
            SELECT es FROM EventSeat es
            JOIN FETCH es.seat s
            WHERE es.event.id = :eventId
            ORDER BY s.section, s.rowLabel, s.seatNumber
            """)
    List<EventSeat> findSeatMap(@Param("eventId") Long eventId);
}
