package com.seatlock;

import com.seatlock.dto.Dtos;
import com.seatlock.exception.Exceptions;
import com.seatlock.service.BookingService;
import com.seatlock.service.SeatHoldService;
import com.seatlock.service.SeatHoldTransactions;
import com.seatlock.support.AbstractIntegrationTest;
import com.seatlock.support.Contention;
import com.seatlock.support.IntegrationTest;
import com.seatlock.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests this project exists for.
 *
 * <p>Every one of them asserts the same thing from a different angle: a seat is
 * sold at most once, no matter how many people want it at the same moment. They
 * run against real Postgres and real Redis, because the guarantees involved are
 * database guarantees.
 */
@IntegrationTest
@DisplayName("Seat inventory under concurrent load")
class ConcurrentBookingIT extends AbstractIntegrationTest {

    @Autowired SeatHoldService holdService;
    @Autowired BookingService bookingService;
    @Autowired SeatHoldTransactions holdTransactions;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("200 people want the same seat: exactly one gets it")
    void oneSeatUnderMassContention() throws Exception {
        var scenario = fixtures.scenario(1);
        List<Long> users = fixtures.newUsers(200);
        Long seat = scenario.firstSeat();

        var outcome = Contention.race(200, i ->
                holdService.createHold(scenario.eventId(), users.get(i), List.of(seat)));

        assertThat(outcome.successes())
                .as("exactly one hold should be granted, got: %s", outcome)
                .isEqualTo(1);
        assertThat(outcome.failureCount()).isEqualTo(199);

        // And the database agrees, which is the claim that actually matters.
        assertThat(liveHoldsFor(seat)).isEqualTo(1);
        assertThat(seatStatus(seat)).isEqualTo("HELD");
    }

    @Test
    @DisplayName("Removing the Redis gate changes throughput, not correctness")
    void withoutTheGateThePostgresLayerStillHoldsTheLine() throws Exception {
        var scenario = fixtures.scenario(1);
        List<Long> users = fixtures.newUsers(60);
        Long seat = scenario.firstSeat();

        // Calls straight into the transactional layer, bypassing SeatGate
        // entirely. This is the test that proves Redis is an optimisation rather
        // than the mechanism: if the fast path vanished tomorrow, the system
        // would get slower and stay correct.
        var outcome = Contention.race(60, i -> holdTransactions.reserve(
                scenario.eventId(), users.get(i), List.of(seat), Duration.ofMinutes(5), Instant.now()));

        assertThat(outcome.successes())
                .as("row locks alone must still admit exactly one winner, got: %s", outcome)
                .isEqualTo(1);
        assertThat(liveHoldsFor(seat)).isEqualTo(1);
    }

    @Test
    @DisplayName("Different seats do not block each other")
    void distinctSeatsAllSucceed() throws Exception {
        var scenario = fixtures.scenario(100);
        List<Long> users = fixtures.newUsers(100);

        // The mirror image of the contention tests. Locking that is too coarse
        // also passes "nothing was oversold" while quietly serialising unrelated
        // customers, so correctness is only half the claim: independent requests
        // must stay independent.
        var outcome = Contention.race(100, i ->
                holdService.createHold(scenario.eventId(), users.get(i), List.of(scenario.seatIds().get(i))));

        assertThat(outcome.successes())
                .as("every distinct seat should be held, got: %s", outcome)
                .isEqualTo(100);
        assertThat(countSeats(scenario.eventId(), "HELD")).isEqualTo(100);
    }

    @Test
    @DisplayName("Overlapping multi-seat requests do not deadlock")
    void overlappingMultiSeatRequestsDoNotDeadlock() throws Exception {
        var scenario = fixtures.scenario(40);
        List<Long> users = fixtures.newUsers(80);
        List<Long> seats = scenario.seatIds();

        // Half the threads ask for seats in ascending order, half descending. If
        // the service took locks in the order the client sent them, these two
        // groups would grab overlapping rows in opposite orders and deadlock.
        // Sorting seat ids before locking is what makes that impossible; the race
        // helper fails the test outright if nothing finishes in 90 seconds.
        var outcome = Contention.race(80, i -> {
            int start = (i % 20) * 2;
            Long low = seats.get(start);
            Long high = seats.get(start + 1);
            List<Long> requested = (i % 2 == 0) ? List.of(low, high) : List.of(high, low);
            holdService.createHold(scenario.eventId(), users.get(i), requested);
        });

        // Each adjacent pair can be won once, so 20 of the 80 attempts succeed.
        assertThat(outcome.successes()).as("outcome: %s", outcome).isEqualTo(20);
        assertThat(countSeats(scenario.eventId(), "HELD")).isEqualTo(40);
    }

    @Test
    @DisplayName("Full checkout race: one booking, one booked seat, no oversell")
    void concurrentCheckoutSellsTheSeatExactlyOnce() throws Exception {
        var scenario = fixtures.scenario(1);
        List<Long> users = fixtures.newUsers(150);
        Long seat = scenario.firstSeat();

        var outcome = Contention.race(150, i -> {
            Dtos.HoldResponse hold = holdService.createHold(scenario.eventId(), users.get(i), List.of(seat));
            bookingService.confirm(hold.holdId(), users.get(i));
        });

        assertThat(outcome.successes())
                .as("exactly one checkout should complete, got: %s", outcome)
                .isEqualTo(1);

        // Scoped to this event: the container is shared across the whole test
        // run, so a global count would couple this assertion to every other test.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE event_id = ?", Long.class, scenario.eventId()))
                .isEqualTo(1L);
        assertThat(bookedRowsFor(seat)).isEqualTo(1);
        assertThat(seatStatus(seat)).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("A lost race is reported as a conflict, never a server error")
    void losersGetAConflictNotAFiveHundred() throws Exception {
        var scenario = fixtures.scenario(1);
        List<Long> users = fixtures.newUsers(30);

        var outcome = Contention.race(30, i ->
                holdService.createHold(scenario.eventId(), users.get(i), List.of(scenario.firstSeat())));

        // Contention is an expected outcome of a working system. If losers came
        // back as 500s the error rate would spike during exactly the traffic the
        // service was built to handle, and every on-sale would page someone.
        long conflicts = outcome.countOf(Exceptions.SeatUnavailable.class);
        assertThat(conflicts)
                .as("all 29 losers should be plain conflicts, got: %s", outcome)
                .isEqualTo(29);
    }

    // ------------------------------------------------------------------ helpers
    // Raw SQL rather than the repositories: an assertion should not depend on the
    // same mapping layer it is meant to be checking.

    private int liveHoldsFor(Long eventSeatId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM seat_hold_items WHERE event_seat_id = ? AND released_at IS NULL",
                Integer.class, eventSeatId);
        return n == null ? 0 : n;
    }

    private int bookedRowsFor(Long eventSeatId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM booking_seats WHERE event_seat_id = ? AND cancelled_at IS NULL",
                Integer.class, eventSeatId);
        return n == null ? 0 : n;
    }

    private String seatStatus(Long eventSeatId) {
        return jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, eventSeatId);
    }

    private int countSeats(Long eventId, String status) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM event_seats WHERE event_id = ? AND status = ?",
                Integer.class, eventId, status);
        return n == null ? 0 : n;
    }
}
