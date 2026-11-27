-- V6 - optimistic lock on payments (see Payment.version).
-- Existing rows start at version 0, like bookings do.
ALTER TABLE courtly.payments
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
