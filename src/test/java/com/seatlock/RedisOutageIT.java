package com.seatlock;

import com.seatlock.dto.Dtos;
import com.seatlock.exception.Exceptions;
import com.seatlock.service.BookingService;
import com.seatlock.service.SeatHoldService;
import com.seatlock.support.AbstractIntegrationTest;
import com.seatlock.support.Contention;
import com.seatlock.support.IntegrationTest;
import com.seatlock.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What happens when Redis is simply gone.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@link com.seatlock.service.SeatGate} is documented as an optimisation
 * rather than a correctness mechanism, and {@code ConcurrentBookingIT} has a
 * test that supposedly demonstrates it. But that test proves something weaker
 * than it appears: it calls <em>past</em> the gate into the transactional layer,
 * which shows the database layer works in isolation — not that the application
 * survives Redis being unreachable.
 *
 * <p>The difference is not academic. Before this test existed,
 * {@code setIfAbsent} threw on a connection failure and the exception travelled
 * straight out of the request, so a Redis outage would have returned 500 for
 * every single booking attempt. The documentation claimed graceful degradation
 * the code did not implement, and every test passed.
 *
 * <p>So the outage is simulated at the boundary that actually breaks: the Redis
 * client itself. Every call throws, exactly as it would against a dead server.
 */
@IntegrationTest
@DisplayName("Booking with Redis unavailable")
class RedisOutageIT extends AbstractIntegrationTest {

    /**
     * Replaces the real client for this context only.
     *
     * <p>Stopping the shared container would have been more literal, but it is
     * shared by every test class in the JVM, so tearing it down mid-run would
     * make unrelated classes fail depending on execution order. Mocking the
     * client keeps the blast radius to this file and is deterministic.
     */
    @MockBean
    StringRedisTemplate redis;

    @Autowired SeatHoldService holdService;
    @Autowired BookingService bookingService;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void redisIsDown() {
        RedisConnectionFailureException down =
                new RedisConnectionFailureException("Unable to connect to Redis");

        // Every entry point the gate uses, not just the one it happens to call
        // first — a partial stub would let the test pass for the wrong reason.
        Mockito.when(redis.opsForValue()).thenThrow(down);
        Mockito.when(redis.execute(Mockito.any(org.springframework.data.redis.core.script.RedisScript.class),
                Mockito.anyList(), Mockito.any())).thenThrow(down);
        Mockito.when(redis.delete(Mockito.anyString())).thenThrow(down);
        Mockito.when(redis.expire(Mockito.anyString(), Mockito.any())).thenThrow(down);
    }

    @Test
    @DisplayName("A hold still succeeds; the request does not fail")
    void holdsStillWorkWithoutRedis() {
        var scenario = fixtures.scenario(3);
        Long user = fixtures.newUser();

        assertThatCode(() ->
                holdService.createHold(scenario.eventId(), user, List.of(scenario.firstSeat())))
                .as("a cache outage must not become a booking outage")
                .doesNotThrowAnyException();

        assertThat(seatStatus(scenario.firstSeat())).isEqualTo("HELD");
    }

    @Test
    @DisplayName("The whole checkout completes with no Redis at all")
    void fullCheckoutWorksWithoutRedis() {
        var scenario = fixtures.scenario(2);
        Long user = fixtures.newUser();

        Dtos.HoldResponse hold = holdService.createHold(
                scenario.eventId(), user, List.of(scenario.firstSeat()));
        Dtos.BookingResponse booking = bookingService.confirm(hold.holdId(), user);

        assertThat(booking.status()).isEqualTo("CONFIRMED");
        assertThat(seatStatus(scenario.firstSeat())).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("With the gate gone entirely, Postgres still sells the seat exactly once")
    void postgresStillHoldsTheLineWithNoGateAtAll() throws Exception {
        var scenario = fixtures.scenario(1);
        List<Long> users = fixtures.newUsers(50);
        Long seat = scenario.firstSeat();

        // The claim the README makes, tested literally: no Redis, full
        // contention, still exactly one winner. Every rejection here comes from
        // a row lock and a unique index, because there is nothing else left.
        var outcome = Contention.race(50, i ->
                holdService.createHold(scenario.eventId(), users.get(i), List.of(seat)));

        assertThat(outcome.successes())
                .as("losing Redis must cost throughput and nothing else, got: %s", outcome)
                .isEqualTo(1);
        assertThat(liveHoldsFor(seat)).isEqualTo(1);
    }

    @Test
    @DisplayName("Releasing a hold still frees the seat when Redis is down")
    void releaseStillWorksWithoutRedis() {
        var scenario = fixtures.scenario(2);
        Long owner = fixtures.newUser();
        Long other = fixtures.newUser();

        Dtos.HoldResponse hold = holdService.createHold(
                scenario.eventId(), owner, List.of(scenario.firstSeat()));

        // forceReleaseAll talks to Redis too. If its failure escaped, the
        // database release would have committed while the caller saw an error.
        assertThatCode(() -> holdService.releaseHold(hold.holdId(), owner))
                .doesNotThrowAnyException();

        assertThat(seatStatus(scenario.firstSeat())).isEqualTo("AVAILABLE");

        // And the seat is genuinely re-sellable, not merely marked available.
        assertThatCode(() ->
                holdService.createHold(scenario.eventId(), other, List.of(scenario.firstSeat())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A genuinely taken seat is still refused, not accidentally granted")
    void contentionIsStillRejectedWithoutRedis() {
        var scenario = fixtures.scenario(1);
        Long first = fixtures.newUser();
        Long second = fixtures.newUser();

        holdService.createHold(scenario.eventId(), first, List.of(scenario.firstSeat()));

        // Failing open at the gate must mean "let the request through to the
        // database", never "assume it is fine". The rejection now comes from the
        // row lock rather than from Redis, but it must still come.
        assertThatCode(() ->
                holdService.createHold(scenario.eventId(), second, List.of(scenario.firstSeat())))
                .isInstanceOf(Exceptions.SeatUnavailable.class);
    }

    private String seatStatus(Long eventSeatId) {
        return jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, eventSeatId);
    }

    private int liveHoldsFor(Long eventSeatId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM seat_hold_items WHERE event_seat_id = ? AND released_at IS NULL",
                Integer.class, eventSeatId);
        return n == null ? 0 : n;
    }
}
