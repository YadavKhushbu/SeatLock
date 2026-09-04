package com.seatlock.support;

import com.seatlock.domain.*;
import com.seatlock.repo.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the minimum inventory a test needs, and nothing more.
 *
 * <p>Each test creates its own venue, event and seats rather than sharing a
 * fixture. Shared state across concurrency tests is how a suite ends up with
 * failures that depend on execution order, which is precisely the class of bug
 * these tests exist to catch.
 */
@Component
public class TestFixtures {

    /** Keeps emails unique across a run without any coordination between tests. */
    private static final AtomicLong SEQ = new AtomicLong();

    private final UserRepository users;
    private final VenueRepository venues;
    private final SeatRepository seats;
    private final EventRepository events;
    private final EventSeatRepository eventSeats;

    public TestFixtures(UserRepository users, VenueRepository venues, SeatRepository seats,
                        EventRepository events, EventSeatRepository eventSeats) {
        this.users = users;
        this.venues = venues;
        this.seats = seats;
        this.events = events;
        this.eventSeats = eventSeats;
    }

    /** An event with {@code seatCount} available seats, on sale right now. */
    @Transactional
    public Scenario scenario(int seatCount) {
        long n = SEQ.incrementAndGet();
        Instant now = Instant.now();

        Venue venue = venues.save(Venue.builder()
                .name("Test Venue " + n).city("Testville").address("1 Test Way").build());

        List<Seat> physical = new ArrayList<>(seatCount);
        for (int i = 1; i <= seatCount; i++) {
            physical.add(Seat.builder()
                    .venue(venue).section("STALLS").rowLabel("A").seatNumber(i).build());
        }
        physical = seats.saveAll(physical);

        Event event = events.save(Event.builder()
                .venue(venue)
                .title("Test Event " + n)
                .description("Created by TestFixtures")
                .startsAt(now.plus(30, ChronoUnit.DAYS))
                .salesOpenAt(now.minus(1, ChronoUnit.HOURS))
                .salesCloseAt(now.plus(29, ChronoUnit.DAYS))
                .status(EventStatus.SCHEDULED)
                .build());

        List<EventSeat> inventory = new ArrayList<>(seatCount);
        for (Seat seat : physical) {
            inventory.add(EventSeat.builder()
                    .event(event).seat(seat).priceCents(100_00L).status(SeatStatus.AVAILABLE).build());
        }
        inventory = eventSeats.saveAll(inventory);

        return new Scenario(event.getId(), inventory.stream().map(EventSeat::getId).sorted().toList());
    }

    @Transactional
    public Long newUser() {
        long n = SEQ.incrementAndGet();
        return users.save(User.builder()
                .email("user" + n + "@test.local")
                .passwordHash("$2a$10$notarealhashnotarealhashnotarealhashnotarealhashno")
                .fullName("Test User " + n)
                .role(Role.ROLE_USER)
                .build()).getId();
    }

    @Transactional
    public List<Long> newUsers(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(newUser());
        }
        return ids;
    }

    public record Scenario(Long eventId, List<Long> seatIds) {
        public Long firstSeat() {
            return seatIds.get(0);
        }
    }
}
