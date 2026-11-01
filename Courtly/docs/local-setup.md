# Local setup

## What you need

- Java 21
- Docker

## Easiest way

```bash
cp .env.example .env
docker-compose up --build
```

Postgres starts first, the app waits for it to be ready, then starts. About 30 seconds total on first run.

## Running just the database

If I want to run the app from IntelliJ or `mvnw spring-boot:run`:

```bash
docker-compose up postgres -d
cp .env.example .env
./mvnw spring-boot:run
```

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
User:     courtly_user (or whatever you put in .env)
Password: from your .env
```

I use DataGrip but any client works.

## Seeded data

The migrations create four courts. The admin account comes from `.env`:

- `ADMIN_EMAIL` / `ADMIN_PASSWORD` - created on startup if it doesn't exist. An existing account's password is never overwritten.
- Older versions seeded `admin@courtly.com` / `Admin123!`. That password is public, so the V3 migration locks the account; setting `ADMIN_EMAIL=admin@courtly.com` unlocks it with your `ADMIN_PASSWORD`.
- Court A - Clay (€25/h)
- Court B - Grass (€30/h)
- Court C - Hard (€20/h)
- Court D - Artificial (€15/h)

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
| `ADMIN_PASSWORD` | - (min 12 chars) |
| `BOOKING_PAYMENT_TIMEOUT` | `15m` |

Generate a JWT secret:
```bash
openssl rand -base64 64
```

## Smoke test

Starts the app and PostgreSQL with docker compose, runs the real Flyway migrations, and checks login, bookings, the overlap rules and error handling over HTTP. CI runs it after the unit tests.

```bash
./scripts/smoke-test.sh
```

Needs Docker, `curl`, `jq` and `openssl`. It runs as a separate compose project (`courtly-smoke`) with throwaway credentials, so your local database is never touched, and removes its containers and volume when it finishes. Ports 8080 and 5432 have to be free.
