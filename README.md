<p><sub>A PROJECT BY STEFAN KABASAJ &nbsp; / &nbsp; JAVA & SPRING BOOT</sub></p>

# Courtly

### Tennis court booking, from reservation to confirmation.

A backend for managing tennis courts, bookings and payment records. Built around the details that make a reservation system work: access control, payment retries, unpaid booking expiry and booking notifications.

**Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Redis · Kafka · Docker**

[Application docs](./Courtly/README.md) &nbsp; / &nbsp; [Design decisions](./Courtly/docs/decisions.md) &nbsp; / &nbsp; [Local setup](./Courtly/docs/local-setup.md) &nbsp; / &nbsp; [Postman collection](./Courtly/Courtly-API.postman_collection.json)

---

## The product

Players register, browse courts, check availability and reserve a slot. Administrators manage courts and bookings. Booking events drive email notifications through a separate service.

Payments use a **simulated gateway** for development. Unpaid bookings expire automatically to release the slot.

## Engineering focus

| Concern | Implementation |
| :--- | :--- |
| Authentication & access | Spring Security, JWT and user/admin roles |
| Payment retries | Idempotency keys return the original result for repeated requests |
| Booking events | Kafka and a transactional outbox |
| Notifications | A separate service sends emails from booking events |
| Login protection | Redis-backed rate limits shared between instances |
| Data changes | PostgreSQL and versioned Flyway migrations |
| Observability | Prometheus metrics, Grafana dashboard, alert rules and request IDs in logs |
| API documentation | Generated OpenAPI specification and Swagger UI |

## Run locally

From the repository root:

```bash
cd Courtly
cp .env.example .env
docker compose up --build
```

Before starting, configure your local environment file, including `JWT_SECRET`, `ADMIN_EMAIL` and an `ADMIN_PASSWORD` of at least 12 characters. See the [setup guide](./Courtly/docs/local-setup.md) for the full configuration.

| Service | Local URL |
| :--- | :--- |
| Health | http://localhost:8080/api/v1/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Development email inbox | http://localhost:8025 |

## Explore the code

| Path | Contents |
| :--- | :--- |
| [`Courtly/src/`](./Courtly/src/) | Application source and tests |
| [`Courtly/notification-service/`](./Courtly/notification-service/) | Event-driven booking emails |
| [`Courtly/docs/`](./Courtly/docs/) | Local setup and design decisions |
| [`Courtly/pom.xml`](./Courtly/pom.xml) | Maven dependencies and build configuration |
| [`Courtly/docker-compose.yml`](./Courtly/docker-compose.yml) | Local container setup |
| [`Courtly/CONTRIBUTING.md`](./Courtly/CONTRIBUTING.md) | Contribution guidelines |

Run the tests from the application directory:

```bash
./mvnw test
```

---

Built by [Stefan Kabasaj](https://github.com/stefankabasaj) · Former NCAA Division I tennis player  
[Portfolio ↗](https://stefankabasaj.me)
