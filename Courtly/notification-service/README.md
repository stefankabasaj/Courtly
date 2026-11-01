# Courtly notification service

Reads booking events from Kafka (`courtly.booking-events`) and emails the player:
booking received, confirmed, cancelled, released.

It never calls the Courtly API - everything an email needs is in the event.

## Running

Part of the main `docker compose up` in `../`. Emails go to Mailpit, inbox at
http://localhost:8025.

From the IDE, with Kafka, Redis and Mailpit running in compose:

```bash
./mvnw spring-boot:run
```

Defaults point at `localhost:9094` (Kafka), `localhost:6379` (Redis) and `localhost:1025` (SMTP).

## What happens when things go wrong

- Same event twice (Kafka redelivery, the API re-sending after a crash): skipped. Sent event ids are kept in Redis for a week.
- Mail server down: retried 3 times, 2s apart, then the message goes to `courtly.booking-events.DLT`.
- Broken JSON or no `eventId`: straight to the DLT, retrying won't fix it.
- Unknown event type: ignored, so the API can add new types before this service knows about them.

## Config

| Variable | Default |
|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` |
| `BOOKING_EVENTS_TOPIC` | `courtly.booking-events` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` |
| `MAIL_FROM` | `Courtly <no-reply@courtly.local>` |
| `SERVER_PORT` | `8081` |
