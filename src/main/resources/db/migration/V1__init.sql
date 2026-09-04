-- ---------------------------------------------------------------------------
-- SeatLock core schema.
--
-- The concurrency guarantees of this application ultimately rest on constraints
-- declared here, not on application code. Application-level locking is an
-- optimisation; these indexes are the proof.
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'ROLE_USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_users_email UNIQUE (email)
);

CREATE TABLE venues (
    id      BIGSERIAL PRIMARY KEY,
    name    VARCHAR(255) NOT NULL,
    city    VARCHAR(128) NOT NULL,
    address VARCHAR(512)
);

CREATE TABLE seats (
    id         BIGSERIAL PRIMARY KEY,
    venue_id   BIGINT       NOT NULL REFERENCES venues (id) ON DELETE CASCADE,
    section    VARCHAR(32)  NOT NULL,
    row_label  VARCHAR(8)   NOT NULL,
    seat_number INT         NOT NULL,
    CONSTRAINT ux_seats_venue_position UNIQUE (venue_id, section, row_label, seat_number)
);

CREATE TABLE events (
    id            BIGSERIAL PRIMARY KEY,
    venue_id      BIGINT       NOT NULL REFERENCES venues (id),
    title         VARCHAR(255) NOT NULL,
    description   TEXT,
    starts_at     TIMESTAMPTZ  NOT NULL,
    sales_open_at TIMESTAMPTZ  NOT NULL,
    sales_close_at TIMESTAMPTZ NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULED',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_events_starts_at ON events (starts_at);

-- One row per (event, seat). This is the row we take a pessimistic lock on.
CREATE TABLE event_seats (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    seat_id    BIGINT        NOT NULL REFERENCES seats (id),
    price_cents BIGINT       NOT NULL,
    status     VARCHAR(16)   NOT NULL DEFAULT 'AVAILABLE',
    version    BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ux_event_seats UNIQUE (event_id, seat_id),
    CONSTRAINT ck_event_seat_status CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED'))
);
CREATE INDEX ix_event_seats_event_status ON event_seats (event_id, status);

CREATE TABLE seat_holds (
    id         UUID PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_hold_status CHECK (status IN ('ACTIVE', 'CONVERTED', 'RELEASED', 'EXPIRED'))
);
-- The reaper scans on this; partial index keeps it small as history grows.
CREATE INDEX ix_seat_holds_active_expiry ON seat_holds (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_seat_holds_user ON seat_holds (user_id);

CREATE TABLE seat_hold_items (
    hold_id       UUID        NOT NULL REFERENCES seat_holds (id) ON DELETE CASCADE,
    event_seat_id BIGINT      NOT NULL REFERENCES event_seats (id) ON DELETE CASCADE,
    -- Set when the parent hold stops being ACTIVE (converted, released or reaped).
    -- Denormalised from seat_holds.status purely so the partial index below is
    -- legal: Postgres index predicates cannot contain subqueries.
    released_at   TIMESTAMPTZ,
    PRIMARY KEY (hold_id, event_seat_id)
);
-- A seat can be held by at most one *live* hold. Enforced by the DB, not by us:
-- two racing hold requests for the same seat cannot both commit.
CREATE UNIQUE INDEX ux_hold_items_seat_live
    ON seat_hold_items (event_seat_id)
    WHERE released_at IS NULL;

CREATE TABLE bookings (
    id             BIGSERIAL PRIMARY KEY,
    reference      VARCHAR(16) NOT NULL,
    event_id       BIGINT      NOT NULL REFERENCES events (id),
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    status         VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    total_cents    BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at   TIMESTAMPTZ,
    CONSTRAINT ux_bookings_reference UNIQUE (reference),
    CONSTRAINT ck_booking_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);
CREATE INDEX ix_bookings_user ON bookings (user_id, created_at DESC);

CREATE TABLE booking_seats (
    id            BIGSERIAL PRIMARY KEY,
    booking_id    BIGINT      NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    event_seat_id BIGINT      NOT NULL REFERENCES event_seats (id),
    price_cents   BIGINT      NOT NULL,
    seat_label    VARCHAR(64) NOT NULL,
    cancelled_at  TIMESTAMPTZ
);

-- ***  The single most important line in this schema.  ***
-- A seat can appear in at most one non-cancelled booking, ever. Even if every
-- lock, cache and application check above it failed simultaneously, Postgres
-- will reject the second insert. This is what makes overselling impossible
-- rather than merely unlikely.
CREATE UNIQUE INDEX ux_booking_seats_no_double_sell
    ON booking_seats (event_seat_id)
    WHERE cancelled_at IS NULL;

CREATE TABLE idempotency_records (
    id             BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id        BIGINT       NOT NULL REFERENCES users (id),
    request_fingerprint VARCHAR(64) NOT NULL,
    response_status INT,
    response_body  TEXT,
    state          VARCHAR(16)  NOT NULL DEFAULT 'IN_PROGRESS',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_idempotency_key_user UNIQUE (idempotency_key, user_id),
    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))
);

-- ShedLock: stops N application instances all running the hold reaper at once.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
