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

## JSON errors from the security layer

Exceptions thrown inside the Spring Security filter chain never reach `GlobalExceptionHandler`. `ApiSecurityErrorHandler` is wired in as the entry point and access-denied handler so 401 and 403 have the same JSON shape as every other error. Without a custom entry point Spring answers unauthenticated requests with 403.

## BookingValidator as a separate class

`BookingService` was getting long and was doing two jobs - orchestrating the booking flow AND validating times. Pulled validation into its own class. Now `BookingService` just calls `bookingValidator.validateBookingTimes(...)` and the rules are testable in isolation.
