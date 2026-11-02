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

## Idempotency keys on payments

The payment endpoint was already safe against double charging - one payment row per booking, enforced by the database. But "safe" there meant the second click got a 409, and a client that timed out had no way to learn what the first request did. With an `Idempotency-Key` header (what Stripe does), the repeat gets the original answer back.

`IdempotencyService` keeps it in Redis, per user. The key is claimed with `SET NX` before the payment runs, so two copies arriving together can't both run - the second gets 409 "still being processed". The claim expires after a minute, so a crash mid-request doesn't lock the key for a day; the finished result is kept for 24 hours. If the payment throws, the transaction rolled back and the claim is dropped, so the same key can be retried. A key sent again with a different booking is refused rather than replayed, because that's a bug in the client that replaying would hide.

One gap, on purpose: if the app dies after the payment commits but before the result reaches Redis, the claim expires and a retry runs the payment again. That retry finds the booking already CONFIRMED and is refused, so nobody is charged twice - the client just gets an error instead of the original answer.

## Monitoring

The question monitoring has to answer is "is it working for players right now", so besides the usual request rates and latencies there are counters for the things that matter here: bookings by what happened to them, payments by result, logins by result, and emails by result. Bookings are counted in `BookingEventRecorder` - every state change already goes through it, so one counter covers them all.

The most useful single number is `courtly_outbox_oldest_unsent_age_seconds`: how long the oldest booking event has been waiting to go to Kafka. Normally a second or two. If it climbs, emails have stopped, whatever the reason (Kafka down, publisher stuck), and nothing else would show it - the API keeps answering 200 the whole time.

Prometheus finds the API replicas through Docker's DNS, so scaling up needs no config change. The alert rules (`monitoring/alerts.yml`) are about symptoms players would notice - instance down, emails stuck or failing, 5xx rate, slow responses - plus a brute-force signal from blocked logins. Locally there's no Alertmanager; the rules show in Prometheus' Alerts tab. In production it would route them to email or Slack.

Every request gets an id (nginx's `$request_id`, or one the app makes up), returned as `X-Request-Id` and printed in every log line. It also travels inside the booking event, so the notification service logs it too: one grep shows a request from the HTTP call to the email. A tracing system (OpenTelemetry + Tempo/Jaeger) would do this properly, with timings per hop; for two services a shared id in the logs gets most of the value.

## Tests against the real database

The rules that matter most live in PostgreSQL itself: the exclusion constraint, `FOR UPDATE SKIP LOCKED` in the expiry job, the advisory lock in the outbox publisher. H2 can't run any of them, so the unit tests mock those queries - which proves nothing about the queries. The `*PostgresTest` classes run them against a real PostgreSQL in a container, on the real Flyway migrations, including two transactions at once for the locking ones.

The smoke test adds the end-to-end version: 30 requests for the same slot at the same moment, spread over both app instances. The app-level overlap check races (each instance sees a free slot), so it's the database constraint that has to turn 29 of them into 409s. It does.

## What the API gives a frontend

A few things exist mainly so a frontend doesn't have to guess:

- `payBy` on PENDING bookings, so it can show a countdown. Worked out in one place (`BookingProperties.payBy`) for the response, the email and the payment check.
- `expiresAt` on login, so it knows when to send the user back to the login page instead of finding out from a 401. There are no refresh tokens - after 24h you log in again.
- `GET /users/me`, for "who's logged in" after a page reload. It reads the DB, so a deactivated account shows as such even though its token still works.
- An OpenAPI spec generated from the code (springdoc), so the client can be built from the real contract.

CORS is off by default. In production the frontend and API sit behind one domain (nginx, or the frontend proxying `/api`), so the browser never makes a cross-origin call. `CORS_ALLOWED_ORIGINS` turns it on for local development with a frontend on another port. No credentials mode - the token goes in a header, never a cookie.

## JSON errors from the security layer

Exceptions thrown inside the Spring Security filter chain never reach `GlobalExceptionHandler`. `ApiSecurityErrorHandler` is wired in as the entry point and access-denied handler so 401 and 403 have the same JSON shape as every other error. Without a custom entry point Spring answers unauthenticated requests with 403.

## Booking emails through Kafka and an outbox

When a booking is created, confirmed, cancelled or expires, the player gets an email. That's done by a separate `notification-service` reading events from Kafka, not by the API itself: sending email is slow and fails in its own ways, and none of that should hold up or break a booking.

The tricky part is getting the event out reliably. Sending to Kafka straight from `BookingService` has two holes: the transaction can roll back after the send (an email about a booking that never happened), or the app can die between commit and send (a booking nobody hears about). So events go into an `outbox_events` table in the same transaction as the booking change (`BookingEventRecorder`, `Propagation.MANDATORY` so it can't be called outside one). `OutboxPublisher` sends unpublished rows to Kafka every second and marks them after Kafka acks.

That's at-least-once: a crash between the ack and the mark sends the event again. The notification service remembers sent event ids in Redis and skips repeats. It checks, sends, then marks - a crash in the middle means one duplicate email rather than a lost one.

Ordering: with two API instances, each publishing its own batch, a booking's CONFIRMED could overtake its CREATED. A Postgres advisory lock lets only one instance publish at a time, oldest first, and events are keyed by booking id, so each booking's events stay in one partition, in order.

Failures on the consumer side: a send that fails (mail server down) is retried 3 times, then parked in `courtly.booking-events.DLT` so it doesn't block everything behind it. Broken JSON skips the retries. Unknown event types are ignored, so the API can add one before the notification service knows about it.

One thing that bit me: Kafka's auto-create. The notification service started first, subscribed to a topic that didn't exist yet, and Kafka created it with 1 partition. The API then asked for 3 and got them, but the consumer only knew about partition 0 - events keyed onto the other two never arrived. Auto-create is off now, and both services declare the topic the same way.

The event JSON is the contract. Each service has its own copy of the record instead of a shared library, so they can be deployed separately; fields can be added, not renamed or removed.

`BookingExpiryJob` used to be one bulk `UPDATE`. It needs to know which bookings it expired now, to record their events, so it locks them with `SELECT ... FOR UPDATE SKIP LOCKED` and updates them one by one. Both instances can run it at the same time and still each booking expires - and gets its email - once.

Why Kafka and not RabbitMQ, which is honestly the more natural fit for "send an email when X happens": events stay in the topic after they're read, so a second consumer (stats, a calendar sync) can be added later and read the same history. That, and Kafka is what I wanted to learn.

## Local time, not UTC

Bookings are wall-clock times at the courts - "10:00" means 10:00 there, whatever zone the server is in. They're stored as plain `TIMESTAMP` / `LocalDateTime`, so `LocalDateTime.now()` has to be in the courts' zone too. Docker containers default to UTC, which put "now" 1-2 hours behind Belgrade: the 2-hour cancel rule and the "this slot is in the past" check were off by that much. `CourtlyApplication` sets the JVM default zone from `APP_TIMEZONE` (default `Europe/Belgrade`) before Spring starts.

One venue, one zone. If Courtly ever covers venues in different zones, times would move to `OffsetDateTime` / `timestamptz` with a zone per court.

## BookingValidator as a separate class

`BookingService` was getting long and was doing two jobs - orchestrating the booking flow AND validating times. Pulled validation into its own class. Now `BookingService` just calls `bookingValidator.validateBookingTimes(...)` and the rules are testable in isolation.
