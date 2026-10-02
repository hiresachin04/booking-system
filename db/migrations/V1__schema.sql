-- Instants are bigint nanoseconds since the epoch rather than timestamptz: PostgreSQL keeps microseconds, and the
-- booking rules compare instants exactly (a hold is free at exactly its expiry), so truncating would move boundaries.

-- Catalog data never changes once a show exists, so it is one document; only seat state below is relational.
CREATE TABLE shows (
    id         text   PRIMARY KEY,
    definition jsonb  NOT NULL,
    starts_at  bigint NOT NULL
);

-- One row per seat per show: the primary key is what makes it impossible for a seat to record two owners.
CREATE TABLE show_seats (
    show_id         text   NOT NULL REFERENCES shows (id),
    seat_id         text   NOT NULL,
    status          text   NOT NULL CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    booking_id      text,
    hold_expires_at bigint,
    PRIMARY KEY (show_id, seat_id),
    CHECK ((status = 'AVAILABLE') = (booking_id IS NULL)),
    CHECK ((status = 'HELD') = (hold_expires_at IS NOT NULL))
);

CREATE TABLE discount_codes (
    code            text    PRIMARY KEY,
    discount        jsonb   NOT NULL,
    valid_from      bigint  NOT NULL,
    valid_until     bigint  NOT NULL,
    min_order_paise bigint  NOT NULL CHECK (min_order_paise >= 0),
    usage_limit     integer NOT NULL CHECK (usage_limit > 0),
    used            integer NOT NULL DEFAULT 0,
    CHECK (valid_until > valid_from),
    CHECK (used >= 0 AND used <= usage_limit)
);

CREATE TABLE bookings (
    id                    text    PRIMARY KEY,
    user_id               text    NOT NULL,
    user_name             text    NOT NULL,
    show_id               text    NOT NULL REFERENCES shows (id),
    seat_ids              text[]  NOT NULL,
    price                 jsonb   NOT NULL,
    hold_expires_at       bigint  NOT NULL,
    discount_code         text    REFERENCES discount_codes (code),
    checkout              jsonb   NOT NULL,
    status                text    NOT NULL,
    payment_id            text,
    payment_pending_since bigint,
    capture_attempts      integer NOT NULL DEFAULT 0,
    next_capture_at       bigint,
    capture_given_up      boolean NOT NULL DEFAULT false,
    refunded_paise        bigint  NOT NULL DEFAULT 0
);
CREATE INDEX bookings_by_status ON bookings (status);
CREATE INDEX bookings_by_user ON bookings (user_id);

-- One delivery per event per listener, so retrying one listener never re-sends to another.
CREATE TABLE notification_outbox (
    id              bigserial PRIMARY KEY,
    event_id        text    NOT NULL,
    event_type      text    NOT NULL,
    booking_id      text    NOT NULL REFERENCES bookings (id),
    listener        text    NOT NULL,
    status          text    NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at bigint  NOT NULL,
    last_error      text,
    sent_at         bigint,
    UNIQUE (event_id, listener)
);
CREATE INDEX notification_outbox_due ON notification_outbox (next_attempt_at) WHERE status = 'PENDING';
