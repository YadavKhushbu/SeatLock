# SeatLock

An event ticketing API built around one requirement: **a seat is sold at most once, no matter how many people click "buy" at the same instant.**

Overselling is the defining failure of ticketing systems. It is easy to build a booking API that works when tested by one person, and the same API will happily sell seat A-14 to nine different customers the moment a popular show goes on sale. This project treats that race as the primary design problem rather than an afterthought, and backs the claim with tests that put 200 concurrent buyers on a single seat and assert that exactly one wins.

Java 17 · Spring Boot 3.3 · PostgreSQL · Redis · Testcontainers

---

## Contents

- [The problem](#the-problem)
- [How correctness is enforced](#how-correctness-is-enforced)
- [Architecture](#architecture)
- [Running it](#running-it)
- [API walkthrough](#api-walkthrough)
- [Tests: the actual proof](#tests-the-actual-proof)
- [Design decisions](#design-decisions)
- [What I would do next](#what-i-would-do-next)

---

## The problem

Booking a seat looks like a single database update. It is not. A realistic checkout has a gap in the middle:

```
user picks seats ──▶ enters card details ──▶ pays
                     └── 30-90 seconds ──┘
```

That gap forces three requirements that pull against each other:

1. **Seats must be reserved during checkout**, or a user loses their seats while typing a card number.
2. **Abandoned checkouts must return inventory automatically**, or a popular show "sells out" at 30% occupancy because nobody finished.
3. **Two people must never both succeed on the same seat**, however close together they arrive.

The third is the hard one, because the window is genuinely tiny and the naive implementation looks correct:

```java
// Wrong, and passes every test written by one person at a time.
if (seat.getStatus() == AVAILABLE) {   // ── thread A and thread B both read AVAILABLE here
    seat.setStatus(BOOKED);            // ── and both write BOOKED here
    bookingRepository.save(booking);
}
```

Nothing about that code looks broken. It fails only under concurrency, which is exactly when a ticketing system is under load.

---

## How correctness is enforced

Three layers, each with a different job. They are deliberately not redundant copies of one another.

```
                    ┌─────────────────────────────────────────────┐
  10,000 requests   │  1. Redis admission gate      SETNX + TTL    │
  for one seat  ───▶│     rejects ~all contenders in <1ms         │
                    │     OPTIMISATION — not a safety guarantee   │
                    └──────────────────┬──────────────────────────┘
                                       │ 1 request
                    ┌──────────────────▼──────────────────────────┐
                    │  2. Postgres row lock    SELECT … FOR UPDATE │
                    │     ordered by id, finite lock_timeout       │
                    │     CORRECTNESS — serialises real contention │
                    └──────────────────┬──────────────────────────┘
                                       │
                    ┌──────────────────▼──────────────────────────┐
                    │  3. Partial unique index                     │
                    │     booking_seats(event_seat_id)             │
                    │       WHERE cancelled_at IS NULL             │
                    │     PROOF — the database refuses, always     │
                    └─────────────────────────────────────────────┘
```

### Layer 1 — Redis is a speed optimisation, and nothing more

When ten thousand people hit one seat, letting all ten thousand reach Postgres means ten thousand transactions queueing on a single row lock. The median request waits behind the entire queue, and the connection pool is exhausted long before the seat is sold. A Redis `SETNX` gate turns that into one winner and 9,999 fast rejections that never check out a database connection.

**It is explicitly not what prevents double-selling.** A single-node Redis lock has no safety guarantee across failover: if the primary dies after granting a lock but before replicating it, a promoted replica grants the same lock again. Systems that treat a Redis lock as the source of truth oversell precisely during an incident, when they can least afford it.

Here, losing Redis entirely costs throughput and nothing else. There is a test that proves this by calling the transactional layer directly with the gate bypassed — 60 threads, one seat, still exactly one winner.

### Layer 2 — Postgres row locks are where correctness actually lives

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT es FROM EventSeat es WHERE es.id IN :ids ORDER BY es.id")
List<EventSeat> lockAllByIdInOrder(@Param("ids") Collection<Long> ids);
```

Two details carry as much weight as the lock itself:

- **`ORDER BY id`, and seat ids sorted before the call.** Without a consistent global lock order, two multi-seat requests deadlock: A locks seat 5 and waits for 9, B locks 9 and waits for 5. Sorting makes that cycle impossible rather than unlikely. There is a test that fires 80 threads at overlapping seat pairs in deliberately opposing orders.
- **A finite wait.** Postgres has no `FOR UPDATE WAIT n` syntax, so JPA's lock-timeout hint cannot be honoured there. The real bound comes from `SET lock_timeout = '3s'` applied to every pooled connection via Hikari's `connection-init-sql`. Without it, one wedged transaction stalls every request for that seat until something upstream times out instead.

### Layer 3 — the database has the final word

```sql
CREATE UNIQUE INDEX ux_booking_seats_no_double_sell
    ON booking_seats (event_seat_id)
    WHERE cancelled_at IS NULL;
```

A seat appears in at most one non-cancelled booking, ever. If every lock, cache and application check above it failed simultaneously, Postgres still rejects the second insert. This is what makes overselling *impossible* rather than merely *unlikely* — and the partial predicate means a cancelled booking releases the seat for resale while the row survives for audit.

The application catches that violation and reports a normal `409`, then logs a warning — because reaching that line means something above it is broken and worth knowing about.

### Holds expire on their own

An abandoned checkout must return its seats without human intervention. A scheduled reaper reclaims expired holds, coordinated by **ShedLock** so that N application instances do not all process the same batch and deadlock against each other. Batches are bounded, each hold commits in its own transaction (`REQUIRES_NEW`), and one failure cannot roll back the other 199.

### Retries do not buy twice

Mobile networks guarantee dropped responses. A client that POSTs a booking, has the response lost, and retries must not be charged twice. Every booking accepts an `Idempotency-Key`; the outcome is recorded against `(key, user)` and replayed verbatim on retry. Reusing a key with a *different* body is rejected as `422` rather than quietly served the old response, because that is a client bug worth surfacing.

---

## Architecture

```
com.seatlock
├── domain/     JPA entities. Business invariants live on the entity
│               (SeatHold.close(), Event.isOnSaleAt()), not in services.
├── repo/       Spring Data repositories. Locking and fetch strategy declared here.
├── service/    SeatGate         Redis admission gate (Lua compare-and-delete)
│               SeatHoldService  orchestration: Redis outside the transaction
│               SeatHoldTransactions  the transactional half, kept separate
│               BookingService   hold → booking, cancellation
│               IdempotencyService, HoldReaper, EventService, AuthService
├── web/        Controllers + one GlobalExceptionHandler for a single error shape.
├── security/   Stateless JWT. Principal carries the user id, so no re-lookup.
└── config/     Security, Redis/ShedLock, OpenAPI, typed properties.
```

**Why `SeatHoldService` and `SeatHoldTransactions` are two classes.** Redis round-trips must happen strictly outside a database transaction. Waiting on a network call with an open transaction pins a pooled connection for its duration, and under load that is how a service runs out of connections while doing almost no work. Splitting the classes makes the boundary a compile-time fact rather than a comment someone will eventually violate.

---

## Running it

Everything runs from a clone. Maven is not required — the wrapper bootstraps it.

```bash
docker compose up --build
```

Then:

| | |
|---|---|
| API | http://localhost:8080/api/v1/events |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Prometheus metrics | http://localhost:8080/actuator/prometheus |

Demo data (three venues, three events, 180 seats each) is seeded by Flyway on first boot.

For dashboards:

```bash
docker compose --profile observability up
```

Grafana at http://localhost:3000, Prometheus at http://localhost:9090.

### Running the app locally against containerised dependencies

```bash
docker compose up -d postgres redis
./mvnw spring-boot:run
```

---

## API walkthrough

Booking is deliberately two steps — hold, then confirm — because that is what the checkout gap requires.

```bash
# 1. Register
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse-battery","fullName":"Ada Lovelace"}' \
  | jq -r .accessToken)

# 2. Browse the seat map
curl -s localhost:8080/api/v1/events/1/seats | jq '.available, .seats[0]'

# 3. Hold two seats (409 SEAT_UNAVAILABLE if someone beat you to them)
HOLD=$(curl -s -X POST localhost:8080/api/v1/events/1/holds \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"eventSeatIds":[1,2]}' | jq -r .holdId)

# 4. Confirm, with an idempotency key
curl -s -X POST localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d "{\"holdId\":\"$HOLD\"}" | jq
```

Running step 4 again with the **same** key returns the same booking and an `Idempotent-Replay: true` header. With a *different* key it returns `409 HOLD_NOT_LIVE`, because the hold is already spent.

Every error uses one envelope:

```json
{
  "timestamp": "2026-09-04T12:34:56Z",
  "status": 409,
  "code": "SEAT_UNAVAILABLE",
  "message": "Seat 14 is already held",
  "path": "/api/v1/events/1/holds"
}
```

---

## Tests: the actual proof

```bash
./mvnw verify
```

Integration tests run against **real Postgres and real Redis** via Testcontainers — never H2. The guarantees under test are `SELECT … FOR UPDATE` semantics and partial unique indexes; an in-memory database implements those differently or not at all, so a passing H2 test would be worse than no test. Without Docker running, the integration tests skip rather than fail, and the unit tests still run.

The suite in `ConcurrentBookingIT`:

| Test | Asserts |
|---|---|
| `oneSeatUnderMassContention` | 200 threads, 1 seat → exactly 1 hold, 199 conflicts |
| `withoutTheGateThePostgresLayerStillHoldsTheLine` | Redis bypassed entirely → still exactly 1 winner |
| `distinctSeatsAllSucceed` | 100 threads, 100 different seats → all 100 succeed |
| `overlappingMultiSeatRequestsDoNotDeadlock` | 80 threads, opposing lock orders → completes, no deadlock |
| `concurrentCheckoutSellsTheSeatExactlyOnce` | Full hold→pay race → 1 booking row, 1 booked seat |
| `losersGetAConflictNotAFiveHundred` | Losers get `409`, never `500` |

Two of those deserve emphasis, because they test the things a "no oversell" assertion alone would miss:

- **`distinctSeatsAllSucceed`** catches locking that is too *coarse*. A table-level lock also passes "nothing was oversold" while serialising every unrelated customer in the venue.
- **`losersGetAConflictNotAFiveHundred`** encodes that contention is a normal outcome. If losing a race returned `500`, the error rate would spike during exactly the traffic the system was built for, and every on-sale would page someone.

Threads are released from a shared `CountDownLatch` rather than merely submitted to a pool. Submitting N tasks staggers them by however long it takes to hand each to a thread — on a fast machine, long enough that most finish before the last starts, so the test never creates contention and passes whether or not the locking works. The pool is also sized to one thread per attempt: with a smaller pool the queued tasks never reach the latch and the harness deadlocks itself rather than testing anything.

### If Testcontainers says "Could not find a valid Docker environment"

Two things bite on recent Docker installs, and both are already handled in this repo:

- **API version.** `docker-java` negotiates Engine API `1.32`, but Docker Engine 29 accepts nothing below `1.40`. The rejection arrives as a bare HTTP 400 that Testcontainers reports as a missing Docker environment, which sends you hunting for socket permissions instead of a version number. Pinned via `-Dapi.version=1.43` in the Surefire config.
- **The named pipe on Windows.** Docker Desktop's legacy `\\.\pipe\docker_engine` is a stub that answers with a pointer to the real endpoint. If detection still fails, put this in `~/.testcontainers.properties`:

  ```properties
  docker.host=npipe:////./pipe/dockerDesktopLinuxEngine
  ```

Check what your daemon actually supports with `docker version --format '{{.Server.MinAPIVersion}}'`.

---

## Design decisions

**Holds as first-class entities rather than a `held_until` column on the seat.**
A hold covers multiple seats bought together, needs its own lifecycle and audit trail, and must be convertible atomically. A timestamp column cannot express "these four seats succeed or fail together".

**`open-in-view: false`.**
Spring Boot's default keeps a persistence context open through view rendering, which turns an accidental lazy access in a controller into a surprise query and holds a connection for the whole request. Off means every fetch is deliberate and visible in the service layer.

**Soft-cancel rather than delete.**
`booking_seats` rows are stamped `cancelled_at` rather than removed. History survives, and the partial unique index still frees the seat for resale.

**Money as `long` cents.**
No `double` anywhere near a price. `BigDecimal` would also be correct; integer cents is simpler and cannot be rounded by accident.

**404 rather than 403 for another user's hold.**
Confirming that a resource exists but belongs to someone else is itself a disclosure.

**Constant-time-ish login.**
A password hash is verified even when the account does not exist, so response timing does not reveal which emails are registered.

**Failing fast on a weak JWT secret.**
The app refuses to start if the secret is under 32 bytes, rather than silently signing with weak key material.

---

## What I would do next

Honest list of what is deliberately not here.

- **Payments.** Confirmation assumes payment succeeded. A real integration needs a saga: reserve → authorise → capture, with compensation when capture fails after the seats are marked sold.
- **A load test.** The concurrency claims are proved by correctness tests, not by measured throughput. Numbers would need Gatling or k6 against a realistic deployment; I would rather state that than quote figures I have not measured.
- **Redis as a read cache.** The seat map is currently read from Postgres every time. It is the hottest read path in the system and the obvious next optimisation, with the usual invalidation-on-write problem to solve.
- **Waiting rooms.** Above some load the right answer is not a faster gate but a queue in front of the sale, which is what large ticketing platforms actually do.
- **Rate limiting per user and per IP**, which the Redis dependency already present would make straightforward.
