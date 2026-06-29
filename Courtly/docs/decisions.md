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

## Partial unique index for double-booking

The application checks for overlaps before saving and gives a clean error message. But two requests at the exact same millisecond can both pass that check.

```sql
CREATE UNIQUE INDEX idx_bookings_no_overlap
    ON courtly.bookings (court_id, start_time, end_time)
    WHERE status <> 'CANCELLED';
```

If both requests slip through the app check, the database rejects the second one. The `WHERE` clause means cancelled bookings free up the slot.

## BookingValidator as a separate class

`BookingService` was getting long and was doing two jobs - orchestrating the booking flow AND validating times. Pulled validation into its own class. Now `BookingService` just calls `bookingValidator.validateBookingTimes(...)` and the rules are testable in isolation.
