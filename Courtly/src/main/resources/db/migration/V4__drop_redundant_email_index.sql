-- V4 - drop idx_users_email.
-- The uq_users_email unique constraint from V1 already comes with its own
-- index on email, so this second one only made every insert and update on
-- users a little slower.
DROP INDEX IF EXISTS courtly.idx_users_email;
