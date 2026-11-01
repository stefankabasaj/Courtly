# Notes on design decisions

Quick notes on why I built things the way I did. Mostly for my future self.

## Three exception types

Started with a single `AppException` that took a status code. Got messy fast - too easy to throw 400 when I meant 404. Split into:

- `BusinessException` (400) - business rule violated
- `ResourceNotFoundException` (404) - thing doesn't exist
- `ConflictException` (409) - duplicate or already taken

Now the exception type is the HTTP code. Hard to get wrong.

## JOIN FETCH on lazy relations

Booking has `@ManyToOne(LAZY)` on user and court. If I don't JOIN FETCH on every query, I get either:

1. `LazyInitializationException` if I access `booking.getUser()` outside the transaction
2. N+1 queries if I access them in a loop inside the transaction

The `WithDetails` query variants in `BookingRepository` solve both.

## 404 instead of 403 for unauthorized access

If someone tries to GET a booking they don't own, the natural response is 403 Forbidden. But that confirms the booking exists. Returning 404 instead leaks no information - the user can't tell if the booking exists at all.

This isn't strictly correct from an HTTP standpoint, but it's better security.

## Soft delete on courts and users

Both have foreign keys pointing at them from `bookings`. Hard delete breaks referential integrity. Soft delete (`active = false`) keeps history intact.

For users specifically, this also means an account can be reactivated by support without losing booking history.

## Two-hour cancellation window

Picked 2 hours arbitrarily. Felt right for a tennis venue - enough time to potentially rebook the slot but not so much that users feel locked in. It's a constant in `BookingService` so easy to change later.

## BCrypt strength 12

Default is 10. Each strength level doubles the time. 12 takes about 250ms per hash. Slow enough that GPU brute force is impractical, fast enough that nobody complains about login time.

## Exclusion constraint for double-booking

The application checks for overlaps before saving and gives a clean error message. But two requests at the exact same millisecond can both pass that check.

V1 added a partial unique index on `(court_id, start_time, end_time)`. That only rejects two bookings with the *same* start and end - 10:00-12:00 and 11:00-13:00 on the same court both got through. V2 replaces it with a PostgreSQL exclusion constraint:

```sql
ALTER TABLE courtly.bookings
    ADD CONSTRAINT excl_bookings_no_overlap
    EXCLUDE USING gist (court_id WITH =, tsrange(start_time, end_time) WITH &&)
    WHERE (status <> 'CANCELLED');
```

If two requests slip through the app check, the database rejects the second one and the API answers 409. Ranges are half-open, so back-to-back slots are fine, and the `WHERE` clause means cancelled bookings free up the slot. It needs the `btree_gist` extension, which V2 creates.

## Failed payments can be retried

A booking has at most one payment row. If the (simulated) gateway declines, the row is kept as FAILED and the next attempt reuses it, so the booking is never stuck in PENDING. Once a payment is SUCCESS the booking is CONFIRMED and further attempts are rejected.

## Admin account from the environment

The first migration seeded `admin@courtly.com` with a password that was published in the README, so every instance had the same admin login. V3 locks that account, and `AdminBootstrap` creates (or unlocks) the admin from `ADMIN_EMAIL` / `ADMIN_PASSWORD` at startup. It never overwrites an existing password, so restarting with a different `ADMIN_PASSWORD` doesn't silently change it.

## Unpaid bookings expire

A PENDING booking holds its slot. Without a deadline anyone could block a court for days without paying. `BookingExpiryJob` runs every minute and cancels PENDING bookings older than `BOOKING_PAYMENT_TIMEOUT`. It is one UPDATE that also bumps `version`, so a payment racing with it fails on the optimistic lock (409) instead of confirming a booking that was just cancelled. `PaymentService` also rejects payments outside the window, which covers the up-to-a-minute gap between runs.

## Login rate limit

`LoginAttemptService` counts failed logins per email (stops guessing one password) and per IP (stops trying a few passwords on many accounts) in a fixed 15-minute window. Unknown emails count the same as wrong passwords, so the limit doesn't reveal which emails exist. Once blocked, even the right password gets 429 - otherwise the switch from 429 to 200 would confirm the guess.

The counters started out in memory. That stopped working the moment there were two instances: nginx alternates between them, so each one only saw half the attempts and an attacker got double the limit. They're in Redis now - one key per email / IP, created with `INCR` and given a TTL in the same Lua script, so a crash can't leave a counter that never expires. Redis expiring the keys also replaced the cleanup job.

If Redis is down, login answers 503 rather than skipping the limit. Failing open would mean unlimited guessing whenever Redis has a bad minute.

The IP is the socket's remote address, not `X-Forwarded-For`, because a client can put anything in that header. In docker compose nginx overwrites the header with the real client address and the app runs with `server.forward-headers-strategy=native`, which trusts it only from a private-network proxy.

## Two instances behind nginx

`docker compose` runs two app replicas behind nginx. It's mostly here to keep the app honest about state: anything kept in one instance's memory is a bug the moment there are two, and the smoke test runs every check through the load balancer.

What had to change: the login counters (Redis, above). What didn't: sessions (there are none, JWT), and `BookingExpiryJob`, which runs on both instances but is a single `UPDATE ... WHERE status = 'PENDING'` - running it twice just finds nothing the second time. A real distributed lock (ShedLock, Redis locks) would only be needed for a job that must not run twice.

## Opening hours and availability

`GET /courts/{id}/availability?date=` returns one slot per opening hour, marked free or taken, so clients don't have to guess and retry. That only makes sense with opening hours, so bookings are now also limited to them (`COURTS_OPENING_HOUR` / `COURTS_CLOSING_HOUR`, default 07:00-23:00). One set of hours for all courts - per-court hours can come later if a venue needs them.

The grid is built from one query for the whole day. A slot is taken if any PENDING or CONFIRMED booking overlaps it, using the same half-open rule as the database constraint, and past slots are never free.

## JSON errors from the security layer

Exceptions thrown inside the Spring Security filter chain never reach `GlobalExceptionHandler`. `ApiSecurityErrorHandler` is wired in as the entry point and access-denied handler so 401 and 403 have the same JSON shape as every other error. Without a custom entry point Spring answers unauthenticated requests with 403.

## Local time, not UTC

Bookings are wall-clock times at the courts - "10:00" means 10:00 there, whatever zone the server is in. They're stored as plain `TIMESTAMP` / `LocalDateTime`, so `LocalDateTime.now()` has to be in the courts' zone too. Docker containers default to UTC, which put "now" 1-2 hours behind Belgrade: the 2-hour cancel rule and the "this slot is in the past" check were off by that much. `CourtlyApplication` sets the JVM default zone from `APP_TIMEZONE` (default `Europe/Belgrade`) before Spring starts.

One venue, one zone. If Courtly ever covers venues in different zones, times would move to `OffsetDateTime` / `timestamptz` with a zone per court.

## BookingValidator as a separate class

`BookingService` was getting long and was doing two jobs - orchestrating the booking flow AND validating times. Pulled validation into its own class. Now `BookingService` just calls `bookingValidator.validateBookingTimes(...)` and the rules are testable in isolation.
