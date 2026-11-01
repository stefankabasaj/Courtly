# Contributing

If you're forking this or working on it with me.

## Setup

```bash
git clone https://github.com/stefankabasaj/Courtly.git
cd Courtly/Courtly
cp .env.example .env
docker compose up postgres -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

## Branches

```
main      protected, what's deployed
develop   integration
feature/  new things, branch from develop
fix/      bug fixes
```

## Commits

Trying to keep these clean:

```
feat:     add payment retry endpoint
fix:      overlap detection edge case at midnight
test:     BookingValidator coverage
refactor: pull time validation out of BookingService
docs:     update API table
```

## Before opening a PR

```bash
./mvnw test
./scripts/smoke-test.sh
git status
```

Confirm `.env` is not staged.

## A few rules

- Controllers stay thin. No business logic, no direct repository calls.
- Services own all business rules. Transaction boundary lives there.
- Repositories: data access only. Use JOIN FETCH if you're touching lazy relations.
- Entities: JPA annotations only, no behaviour.
- Exceptions: one type per HTTP status. Don't mix them.
- Never edit an applied migration. Always add a new `V{n}__name.sql`.
