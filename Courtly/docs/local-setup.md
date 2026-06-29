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

First migration creates an admin and four courts:

- `admin@courtly.com` / `Admin123!`
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

Generate a JWT secret:
```bash
openssl rand -base64 64
```
