#!/usr/bin/env bash
# End-to-end smoke test: starts the real app + PostgreSQL with docker compose,
# so Flyway migrations (including V2) run against a real database, then checks
# the main flows over HTTP.
#
# Usage (from the Courtly/ folder):  ./scripts/smoke-test.sh
# Needs: docker (with compose), curl, jq.
# Creates a throwaway .env if none exists and tears everything down at the end.

set -euo pipefail

BASE="http://localhost:8080/api/v1"
CREATED_ENV=false

cleanup() {
    status=$?
    if [ "$status" -ne 0 ]; then
        echo "---- smoke test FAILED, app logs follow ----"
        docker compose logs app --tail 150 || true
    fi
    docker compose down -v >/dev/null 2>&1 || true
    if [ "$CREATED_ENV" = true ]; then rm -f .env; fi
    exit "$status"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok   $*"; }

# expect <expected status> <description> <curl args...>
# prints the response body, fails if the status differs
expect() {
    local want=$1 what=$2; shift 2
    local out code body
    out=$(curl -s -w $'\n%{http_code}' "$@")
    code=${out##*$'\n'}
    body=${out%$'\n'*}
    [ "$code" = "$want" ] || fail "$what: expected HTTP $want, got $code. Body: $body"
    pass "$what ($code)"
    RESPONSE=$body
}

if [ ! -f .env ]; then
    CREATED_ENV=true
    cat > .env <<EOF
POSTGRES_DB=courtly
POSTGRES_USER=courtly_user
POSTGRES_PASSWORD=$(openssl rand -hex 16)
DB_HOST=localhost
DB_PORT=5432
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
JWT_EXPIRATION_MS=3600000
EOF
fi

echo "Starting postgres + app..."
docker compose up -d --build

echo "Waiting for the app to be healthy..."
for i in $(seq 1 60); do
    if curl -sf "$BASE/health" >/dev/null; then break; fi
    [ "$i" -eq 60 ] && fail "app did not become healthy within 3 minutes"
    sleep 3
done
pass "health endpoint"

# --- security -----------------------------------------------------------------
expect 401 "no token is 401 with a JSON body" "$BASE/courts"
[ "$(jq -r .status <<<"$RESPONSE")" = "401" ] || fail "401 body is not the standard error JSON"

# --- seeded admin (fixed in V2) -------------------------------------------------
expect 200 "seeded admin can log in" -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"email":"admin@courtly.com","password":"Admin123!"}'
ADMIN_TOKEN=$(jq -r .token <<<"$RESPONSE")

expect 200 "list courts" "$BASE/courts" -H "Authorization: Bearer $ADMIN_TOKEN"
COURT_ID=$(jq -r '.[0].id' <<<"$RESPONSE")
[ "$(jq length <<<"$RESPONSE")" -eq 4 ] || fail "expected 4 seeded courts"

# --- regular user ---------------------------------------------------------------
EMAIL="smoke-$(date +%s)@courtly.test"
expect 201 "register user" -X POST "$BASE/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"Password1!\"}"

expect 200 "user login" -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"Password1!\"}"
USER_TOKEN=$(jq -r .token <<<"$RESPONSE")

expect 403 "user cannot create courts" -X POST "$BASE/courts" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"name":"Nope","surface":"CLAY","pricePerHour":10}'

# --- bookings -------------------------------------------------------------------
DAY=$(date -u -d '+3 days' +%Y-%m-%d)
expect 201 "book 10-12" -X POST "$BASE/bookings" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"courtId\":\"$COURT_ID\",\"startTime\":\"${DAY}T10:00:00\",\"endTime\":\"${DAY}T12:00:00\"}"
BOOKING_ID=$(jq -r .id <<<"$RESPONSE")
[ "$(jq -r .totalPrice <<<"$RESPONSE")" != "null" ] || fail "booking has no price"

expect 409 "overlapping 11-13 is rejected" -X POST "$BASE/bookings" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"courtId\":\"$COURT_ID\",\"startTime\":\"${DAY}T11:00:00\",\"endTime\":\"${DAY}T13:00:00\"}"

expect 201 "back-to-back 12-14 is allowed" -X POST "$BASE/bookings" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"courtId\":\"$COURT_ID\",\"startTime\":\"${DAY}T12:00:00\",\"endTime\":\"${DAY}T14:00:00\"}"

# --- database constraint from V2 ------------------------------------------------
# bypass the app and insert an overlapping row directly: the exclusion
# constraint must reject it even though no app-level check runs
set -a; . ./.env; set +a
USER_ID=$(docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
    "SELECT id FROM courtly.users WHERE email = '$EMAIL'")
if docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c \
    "INSERT INTO courtly.bookings (user_id, court_id, start_time, end_time, total_price)
     VALUES ('$USER_ID', '$COURT_ID', '${DAY} 10:30', '${DAY} 11:30', 25)" >/dev/null 2>&1; then
    fail "database accepted an overlapping booking (V2 exclusion constraint missing)"
fi
pass "database rejects overlapping booking inserted directly"

# --- ownership ------------------------------------------------------------------
expect 200 "admin can view the user's booking" "$BASE/bookings/$BOOKING_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

expect 200 "user cancels own pending booking" -X PATCH "$BASE/bookings/$BOOKING_ID/cancel" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"reason":"smoke test"}'
[ "$(jq -r .status <<<"$RESPONSE")" = "CANCELLED" ] || fail "booking not cancelled"

expect 201 "cancelled slot can be booked again" -X POST "$BASE/bookings" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"courtId\":\"$COURT_ID\",\"startTime\":\"${DAY}T10:00:00\",\"endTime\":\"${DAY}T12:00:00\"}"

# --- error handling -------------------------------------------------------------
expect 400 "malformed JSON is 400, not 500" -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' -d '{not json'

echo
echo "Smoke test passed."
