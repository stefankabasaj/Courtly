# Courtly


REST API for managing tennis court reservations. Java 21, Spring Boot 3.5, PostgreSQL.


Users register, log in, browse courts, book a slot, and pay. Admins manage courts and can see all bookings.


## Quick start


```bash
git clone https://github.com/stefankabasaj/Courtly.git
cd Courtly/Courtly
cp .env.example .env
docker compose up --build
```


Health check:
```bash
curl http://localhost:8080/api/v1/health
```


Admin account: set `ADMIN_EMAIL` and `ADMIN_PASSWORD` (at least 12 characters) in `.env` before the first start. The app creates the account on startup. No admin password is stored in the repository.


For more setup options see [docs/local-setup.md](docs/local-setup.md).


## Stack


- Java 21, Spring Boot 3.5
- Spring Security with JWT (HMAC, signed with `JWT_SECRET`)
- Spring Data JPA + Hibernate
- PostgreSQL 16, Flyway migrations
- Redis (login rate limit shared between instances)
- nginx in front of two app instances
- Docker, Docker Compose
- JUnit 5, Mockito


## API


All protected endpoints need `Authorization: Bearer <token>`.

Times are the courts' local time (`APP_TIMEZONE`, default `Europe/Belgrade`) and are sent without an offset, e.g. `2026-11-05T10:00:00`.


### Auth


| Method | Path | Returns |
|---|---|---|
| POST | `/api/v1/auth/register` | 201 user |
| POST | `/api/v1/auth/login` | 200 + token |


### Courts


| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/courts` | any authenticated user |
| GET | `/api/v1/courts/{id}` | any authenticated user |
| GET | `/api/v1/courts/{id}/availability?date=YYYY-MM-DD` | any authenticated user (hourly slots, free or taken) |
| POST | `/api/v1/courts` | admin |
| PUT | `/api/v1/courts/{id}` | admin |
| DELETE | `/api/v1/courts/{id}` | admin (soft delete) |


### Bookings


| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/v1/bookings` | user | 1-4h, on the hour, within opening hours (default 07:00-23:00) |
| GET | `/api/v1/bookings/my` | user | own bookings |
| GET | `/api/v1/bookings/{id}` | user/admin | users see own only |
| PATCH | `/api/v1/bookings/{id}/cancel` | user | 2h notice, PENDING only |
| PATCH | `/api/v1/bookings/{id}/admin-cancel` | admin | reason required |
| GET | `/api/v1/bookings?page=0&size=20` | admin | all bookings, newest first, max 100 per page |


### Payments


| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/v1/payments/{bookingId}` | booking owner | PENDING bookings only. A declined payment can be retried |
| GET | `/api/v1/payments/booking/{bookingId}` | booking owner / admin | 404 for anyone else |


The payment gateway is simulated and approves about 90% of payments.

A booking has to be paid within `BOOKING_PAYMENT_TIMEOUT` (default 15 minutes). Unpaid bookings are then cancelled automatically with the reason `Not paid in time`, which frees the slot.


### Errors


Every error, including 401 and 403 from the security layer, has the same JSON shape:
`status`, `error`, `message`, `path`, `timestamp`, and `fieldErrors` for validation failures (422).
Missing or invalid token: 401. Valid token without the required role: 403.

After 5 failed logins for one email, or 20 from one IP, within 15 minutes, login answers 429 with a `Retry-After` header until the window ends. That includes the right password, so the limit can't be used to confirm a guess.
