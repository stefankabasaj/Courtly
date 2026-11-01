-- V2 - two fixes to the initial schema.
-- V1 is already applied on existing databases and must not be edited, so both
-- fixes live here.

-- 1) SEEDED ADMIN PASSWORD ---------------------------------------------------
-- The hash seeded in V1 does not match the documented password (Admin123!), so
-- the seeded admin could never log in. Replace it with a verified BCrypt hash
-- (strength 12). The WHERE clause leaves the account alone if the password has
-- already been changed.
UPDATE courtly.users
SET    password_hash = '$2b$12$xG3s3s5rYZgu8ornxaJnfueA17n1dKUjbfbQ08hidfqBDApWgnT4.'
WHERE  email = 'admin@courtly.com'
  AND  password_hash = '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewfIoOhZv6.8xyMW';

-- 2) DOUBLE-BOOKING PROTECTION -----------------------------------------------
-- idx_bookings_no_overlap from V1 only rejects two bookings with the exact same
-- (court, start, end). A 10:00-12:00 booking and an 11:00-13:00 booking on the
-- same court both pass it. An exclusion constraint rejects any overlapping
-- range on the same court. Ranges are half-open, so back-to-back slots
-- (10:00-12:00 then 12:00-14:00) are still allowed, and CANCELLED bookings are
-- ignored so their slots can be reused.
CREATE EXTENSION IF NOT EXISTS btree_gist SCHEMA public;

ALTER TABLE courtly.bookings
    ADD CONSTRAINT excl_bookings_no_overlap
    EXCLUDE USING gist (
        court_id WITH =,
        tsrange(start_time, end_time) WITH &&
    )
    WHERE (status <> 'CANCELLED');

-- The exclusion constraint makes the V1 unique index redundant.
DROP INDEX IF EXISTS courtly.idx_bookings_no_overlap;
