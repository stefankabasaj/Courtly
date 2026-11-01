# Courtly

### Tennis court booking API

A Java and Spring Boot backend for managing courts, reservations and payment records. Courtly brings together my software work and my background in competitive tennis.

**Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Redis · Docker · JWT**

## What the application covers

- Account registration and login with JWT authentication.
- Court browsing and administration.
- Reservation creation, cancellation and booking history.
- Payment endpoints and role-based access for users and administrators.
- Validation, structured error responses and database migrations.

## Start here

The application lives in the [Courtly](./Courtly/) directory. These links point to its existing documentation.

| Guide | Contents |
| --- | --- |
| [Application README](./Courtly/README.md) | Endpoint tables, project structure and tests |
| [Local setup](./Courtly/docs/local-setup.md) | Development setup options |
| [Design decisions](./Courtly/docs/decisions.md) | Architecture and implementation choices |
| [Contributing](./Courtly/CONTRIBUTING.md) | Contribution guidelines |
| [Postman collection](./Courtly/Courtly-API.postman_collection.json) | Requests for exploring the API |

## Local development

From the repository root:

```bash
cd Courtly
cp .env.example .env
docker compose up --build
```

Review the values in your local environment file before starting. The health endpoint is `http://localhost:8080/api/v1/health`.

To run the test suite from the application directory:

```bash
./mvnw test
```

## Repository structure

| Path | Purpose |
| --- | --- |
| `Courtly/src/` | Application source and tests |
| `Courtly/docs/` | Setup instructions and design notes |
| `Courtly/pom.xml` | Maven configuration |
| `Courtly/docker-compose.yml` | Local container configuration |

---

Built by [Stefan Kabasaj](https://github.com/stefankabasaj).
