-- V1__init.sql - Courtly - initial schema
-- Never edit this file after it has been applied to a database.
-- Every schema change must go in a new V{n}__description.sql file.
-- Flyway tracks applied migrations by checksum and will fail startup
-- if an applied migration has been modified.

CREATE SCHEMA IF NOT EXISTS courtly;

-- USERS ----------------------------------------------------------------------
CREATE TABLE courtly.users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER'
                               CHECK (role IN ('USER','ADMIN')),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_email ON courtly.users (email);

-- COURTS ---------------------------------------------------------------------
CREATE TABLE courtly.courts (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(100)   NOT NULL,
    surface        VARCHAR(20)    NOT NULL
                                  CHECK (surface IN ('CLAY','GRASS','HARD','ARTIFICIAL')),
    price_per_hour NUMERIC(10,2)  NOT NULL,
    active         BOOLEAN        NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_courts_name  UNIQUE (name),
    CONSTRAINT chk_court_price CHECK (price_per_hour > 0)
);

-- BOOKINGS -------------------------------------------------------------------
CREATE TABLE courtly.bookings (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    version             BIGINT        NOT NULL DEFAULT 0,
    user_id             UUID          NOT NULL REFERENCES courtly.users(id),
    court_id            UUID          NOT NULL REFERENCES courtly.courts(id),
    start_time          TIMESTAMP     NOT NULL,
    end_time            TIMESTAMP     NOT NULL,
    total_price         NUMERIC(10,2) NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                                      CHECK (status IN ('PENDING','CONFIRMED','CANCELLED')),
    cancellation_reason VARCHAR(500),
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_booking_times CHECK (end_time > start_time),
    CONSTRAINT chk_booking_price CHECK (total_price > 0)
);

-- "my bookings" query: WHERE user_id = ?
CREATE INDEX idx_bookings_user_id
    ON courtly.bookings (user_id);

-- overlap detection: WHERE court_id = ? AND start_time < ? AND end_time > ?
CREATE INDEX idx_bookings_court_time
    ON courtly.bookings (court_id, start_time, end_time);

-- status filtering
CREATE INDEX idx_bookings_status
    ON courtly.bookings (status);

-- Database-level safety net for double bookings.
-- Two slots overlap when start_a < end_b AND end_a > start_b.
-- CANCELLED rows are excluded so cancelled slots are reusable.
CREATE UNIQUE INDEX idx_bookings_no_overlap
    ON courtly.bookings (court_id, start_time, end_time)
    WHERE status <> 'CANCELLED';

-- PAYMENTS -------------------------------------------------------------------
CREATE TABLE courtly.payments (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id     UUID          NOT NULL REFERENCES courtly.bookings(id),
    amount         NUMERIC(10,2) NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                                  CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    payment_method VARCHAR(30)   NOT NULL,
    failure_reason VARCHAR(500),
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMP,

    CONSTRAINT uq_payments_booking UNIQUE (booking_id),
    CONSTRAINT chk_payment_amount  CHECK (amount > 0)
);

-- SEED DATA ------------------------------------------------------------------
-- Admin: admin@courtly.com / Admin123!  (BCrypt strength 12)
INSERT INTO courtly.users (email, password_hash, role, active)
VALUES (
    'admin@courtly.com',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewfIoOhZv6.8xyMW',
    'ADMIN',
    TRUE
);

INSERT INTO courtly.courts (name, surface, price_per_hour, active) VALUES
    ('Court A - Clay',       'CLAY',       25.00, TRUE),
    ('Court B - Grass',      'GRASS',      30.00, TRUE),
    ('Court C - Hard',       'HARD',       20.00, TRUE),
    ('Court D - Artificial', 'ARTIFICIAL', 15.00, TRUE);
