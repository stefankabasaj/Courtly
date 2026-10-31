# Courtly


REST API for managing tennis court reservations. Java 21, Spring Boot 3.2, PostgreSQL.


Users register, log in, browse courts, book a slot, and pay. Admins manage courts and can see all bookings.


## Quick start


```bash
git clone https://github.com/stefankabasaj/Courtly.git
cd Courtly/Courtly
cp .env.example .env
docker-compose up --build
```


Health check:
```bash
curl http://localhost:8080/api/v1/health
```


Seeded admin: `admin@courtly.com` / `Admin123!`


For more setup options see [docs/local-setup.md](docs/local-setup.md).


## Stack


- Java 21, Spring Boot 3.2
- Spring Security with JWT (HMAC-SHA256)
- Spring Data JPA + Hibernate
- PostgreSQL 16, Flyway migrations
- Docker, docker-compose
- JUnit 5, Mockito


## API


All protected endpoints need `Authorization: Bearer <token>`.


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
| POST | `/api/v1/courts` | admin |
| PUT | `/api/v1/courts/{id}` | admin |
| DELETE | `/api/v1/courts/{id}` | admin (soft delete) |


### Bookings


| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/v1/bookings` | user | 1-4h, on the hour |
| GET | `/api/v1/bookings/my` | user | own bookings |
| GET | `/api/v1/bookings/{id}` | user/admin | users see own only |
| PATCH | `/api/v1/bookings/{id}/cancel` | user | 2h notice, PENDING only |
| DELETE | `/api/v1/bookings/{id}/admin-cancel` | admin | reason required |
