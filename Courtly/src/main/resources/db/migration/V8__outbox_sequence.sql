-- V8 - send outbox events in insert order, not by created_at.
-- created_at comes from the replica's clock; a database sequence is one order
-- every replica agrees on. A booking's later event is always inserted after its
-- earlier one committed, so per booking the sequence is the real order.
-- Existing rows get numbers too (in no particular order - they're almost all sent).
ALTER TABLE courtly.outbox_events
    ADD COLUMN seq BIGSERIAL;

DROP INDEX IF EXISTS courtly.idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished
    ON courtly.outbox_events (seq)
    WHERE published_at IS NULL;
