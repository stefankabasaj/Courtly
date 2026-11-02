# Local setup

## What you need

- Java 21
- Docker

## Easiest way

```bash
cp .env.example .env
docker compose up --build
```

Postgres, Redis, Kafka and Mailpit start first, then two app instances, nginx in front of them on port 8080, and the notification service. A couple of minutes on the first run (it builds two images).

- API: http://localhost:8080 (Swagger UI at /swagger-ui.html)
- Emails: http://localhost:8025 (Mailpit catches everything, nothing really gets sent)

To see which instance answered what: `docker compose logs -f nginx`.

## Running just the database

If I want to run the app from IntelliJ or `mvnw spring-boot:run`:

```bash
cp .env.example .env
docker compose up postgres redis kafka mailpit -d
set -a; source .env; set +a   # Spring doesn't read .env by itself
./mvnw spring-boot:run
```

In IntelliJ, put the same variables in the run configuration instead (or use the EnvFile plugin).

## Running tests

```bash
./mvnw test
```

Tests use H2 with PostgreSQL compatibility mode. Flyway is disabled - Hibernate creates the schema from entities directly.

## Connecting to the database

When Postgres is running:

```
Host:     localhost
Port:     5432
Database: courtly
Schema:   courtly
User:     POSTGRES_USER from your .env
Password: from your .env
```

I use DataGrip but any client works.

## Seeded data

The migrations create four courts:

- Court A - Clay (€25/h)
- Court B - Grass (€30/h)
- Court C - Hard (€20/h)
- Court D - Artificial (€15/h)

The admin account comes from `.env`. `ADMIN_EMAIL` / `ADMIN_PASSWORD` is created on startup if it doesn't exist, and an existing account's password is never overwritten.

Older versions seeded `admin@courtly.com` / `Admin123!`. That password is public, so the V3 migration locks the account. Setting `ADMIN_EMAIL=admin@courtly.com` unlocks it with your `ADMIN_PASSWORD`.

## Environment variables

| Variable | Default |
|---|---|
| `POSTGRES_DB` | `courtly` |
| `POSTGRES_USER` | - |
| `POSTGRES_PASSWORD` | - |
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `JWT_SECRET` | - (min 32 chars) |
| `JWT_EXPIRATION_MS` | `86400000` (24h) |
| `ADMIN_EMAIL` | - |
| `ADMIN_PASSWORD` | - (12 chars to 72 bytes) |
| `APP_TIMEZONE` | `Europe/Belgrade` |
| `BOOKING_PAYMENT_TIMEOUT` | `15m` |
| `BOOKING_EXPIRY_CHECK_INTERVAL_MS` | `60000` |
| `COURTS_OPENING_HOUR` | `7` |
| `COURTS_CLOSING_HOUR` | `23` (24 = until midnight) |
| `LOGIN_MAX_FAILURES_PER_EMAIL` | `5` |
| `LOGIN_MAX_FAILURES_PER_IP` | `20` |
| `LOGIN_FAILURE_WINDOW` | `15m` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` |
| `BOOKING_EVENTS_TOPIC` | `courtly.booking-events` |
| `OUTBOX_PUBLISH_INTERVAL_MS` | `1000` |
| `CORS_ALLOWED_ORIGINS` | - (off; e.g. `http://localhost:3000` for a frontend dev server) |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `SERVER_PORT` | `8080` |

Generate a JWT secret:
```bash
openssl rand -base64 64
```

## Smoke test

Starts the whole stack with docker compose and checks it end to end over HTTP: migrations, login and its rate limit across both instances, bookings and the overlap rules, payments, expiry, the emails in Mailpit (including that a replayed event isn't emailed twice and a broken message lands in the DLT), and error handling. CI runs it after the unit tests.

```bash
./scripts/smoke-test.sh
```

Needs Docker, `curl`, `jq` and `openssl`. It runs as a separate compose project (`courtly-smoke`) with throwaway credentials, so your local database is never touched, and removes its containers and volume when it finishes. Ports 8080, 5432, 6379, 9094 and 8025 have to be free.
