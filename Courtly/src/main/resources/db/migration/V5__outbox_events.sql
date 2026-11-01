-- V5 - transactional outbox for booking events.
-- A booking change and its event are written in the same transaction, so an
-- event exists exactly when the change committed. OutboxPublisher sends the
-- unpublished rows to Kafka and stamps published_at.

CREATE TABLE courtly.outbox_events (
    id           UUID          PRIMARY KEY,           -- also the event id consumers dedupe on
    aggregate_id UUID          NOT NULL,              -- the booking; used as the Kafka key
    event_type   VARCHAR(50)   NOT NULL,
    payload      VARCHAR(4000) NOT NULL,              -- the event as JSON
    created_at   TIMESTAMP     NOT NULL,
    published_at TIMESTAMP
);

-- the publisher only ever looks for unpublished rows, oldest first
CREATE INDEX idx_outbox_unpublished
    ON courtly.outbox_events (created_at)
    WHERE published_at IS NULL;
