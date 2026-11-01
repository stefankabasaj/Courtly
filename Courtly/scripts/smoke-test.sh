#!/usr/bin/env bash
# End-to-end smoke test: starts the real app + PostgreSQL with docker compose,
# so Flyway migrations run against a real database, then checks the main flows
# over HTTP.
#
# Usage (from the Courtly/ folder):  ./scripts/smoke-test.sh
# Needs: docker (with compose), curl, jq, openssl.
#
# Runs as its own compose project with throwaway credentials, so it never
# touches your local stack's database. It does need ports 8080 and 5432 free.

set -euo pipefail

BASE="http://localhost:8080/api/v1"
COMPOSE=(docker compose -p courtly-smoke)
CREATED_ENV=false

# Throwaway settings. Shell variables win over .env in docker-compose.yml, so
# your own .env (if you have one) is never used for any of these.
export POSTGRES_DB=courtly
export POSTGRES_USER=courtly_smoke
export POSTGRES_PASSWORD="$(openssl rand -hex 16)"
export JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
export ADMIN_EMAIL=admin@courtly.com
export ADMIN_PASSWORD="$(openssl rand -hex 12)"
# short payment window so expiry can be checked within the test
export BOOKING_PAYMENT_TIMEOUT=30s
export BOOKING_EXPIRY_CHECK_INTERVAL_MS=2000

cleanup() {
    status=$?
    if [ "$status" -ne 0 ]; then
        echo "---- smoke test FAILED, app logs follow ----"
        "${COMPOSE[@]}" logs app --tail 150 || true
    fi
    "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
    if [ "$CREATED_ENV" = true ]; then rm -f .env; fi
    exit "$status"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "ok   $*"; }

# expect <expected status> <description> <curl args...>
# stores the response body in RESPONSE, fails if the status differs
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

# book <description> <expected status> <start hour> <end hour>
book() {
    expect "$2" "$1" -X POST "$BASE/bookings" \
        -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
        -d "{\"courtId\":\"$COURT_ID\",\"startTime\":\"${DAY}T$3:00:00\",\"endTime\":\"${DAY}T$4:00:00\"}"
}

psql_smoke() {
    "${COMPOSE[@]}" exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
}

# docker-compose.yml lists .env as an env_file, so it has to exist
if [ ! -f .env ]; then
    CREATED_ENV=true
    touch .env
fi

echo "Starting postgres + app..."
"${COMPOSE[@]}" up -d --build

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

# --- admin (V3 locks the published seed, AdminBootstrap unlocks it) -------------
expect 401 "published seed password no longer works" -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"email":"admin@courtly.com","password":"Admin123!"}'

expect 200 "admin logs in with ADMIN_PASSWORD" -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
ADMIN_TOKEN=$(jq -r .token <<<"$RESPONSE")
[ "$(jq -r .role <<<"$RESPONSE")" = "ADMIN" ] || fail "bootstrapped account is not ADMIN"

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
book "book 10-12" 201 10 12
BOOKING_ID=$(jq -r .id <<<"$RESPONSE")
[ "$(jq -r .totalPrice <<<"$RESPONSE")" != "null" ] || fail "booking has no price"

book "overlapping 11-13 is rejected" 409 11 13
book "back-to-back 12-14 is allowed" 201 12 14
book "outside opening hours (05-06) is rejected" 400 05 06

# --- availability ------------------------------------------------------------------
expect 200 "availability for the day" "$BASE/courts/$COURT_ID/availability?date=$DAY" \
    -H "Authorization: Bearer $USER_TOKEN"
slot() { jq -r --arg t "${DAY}T$1:00:00" '.slots[] | select(.startTime == $t) | .available' <<<"$RESPONSE"; }
[ "$(jq '.slots | length' <<<"$RESPONSE")" -eq 16 ] || fail "expected 16 hourly slots (07-23)"
for h in 10 11 12 13; do [ "$(slot $h)" = "false" ] || fail "slot $h:00 should be taken"; done
for h in 09 14; do [ "$(slot $h)" = "true" ] || fail "slot $h:00 should be free"; done
pass "booked hours (10-14) are taken, 09 and 14 are free"

# --- database constraint from V2 ------------------------------------------------
# bypass the app and insert an overlapping row directly: the exclusion
# constraint must reject it even though no app-level check runs
USER_ID=$(psql_smoke -tAc "SELECT id FROM courtly.users WHERE email = '$EMAIL'")
if psql_smoke -v ON_ERROR_STOP=1 -c \
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

book "cancelled slot can be booked again" 201 10 12

# --- unpaid bookings expire ------------------------------------------------------
book "book 16-17 and leave it unpaid" 201 16 17
UNPAID_ID=$(jq -r .id <<<"$RESPONSE")

echo "Waiting for the ${BOOKING_PAYMENT_TIMEOUT} payment window to pass..."
for i in $(seq 1 45); do
    STATUS=$(curl -s "$BASE/bookings/$UNPAID_ID" -H "Authorization: Bearer $USER_TOKEN" | jq -r .status)
    [ "$STATUS" = "CANCELLED" ] && break
    [ "$i" -eq 45 ] && fail "unpaid booking still $STATUS after 90s"
    sleep 2
done
pass "unpaid booking was cancelled automatically"

expect 200 "cancellation reason says why" "$BASE/bookings/$UNPAID_ID" \
    -H "Authorization: Bearer $USER_TOKEN"
[ "$(jq -r .cancellationReason <<<"$RESPONSE")" = "Not paid in time" ] || fail "unexpected cancellation reason"

expect 400 "expired booking can't be paid" -X POST "$BASE/payments/$UNPAID_ID" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"paymentMethod":"CREDIT_CARD"}'

book "the freed slot can be booked again" 201 16 17

# --- login rate limit -------------------------------------------------------------
BRUTE="brute-$(date +%s)@courtly.test"
expect 201 "register a second user" -X POST "$BASE/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$BRUTE\",\"password\":\"Password1!\"}"
for i in 1 2 3 4 5; do
    expect 401 "wrong password #$i" -X POST "$BASE/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$BRUTE\",\"password\":\"wrong-$i\"}"
done
expect 429 "6th attempt is blocked, even with the right password" -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$BRUTE\",\"password\":\"Password1!\"}"
RETRY_AFTER=$(curl -s -o /dev/null -D - -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$BRUTE\",\"password\":\"Password1!\"}" | tr -d '\r' | awk -F': ' 'tolower($1)=="retry-after"{print $2}')
[ -n "$RETRY_AFTER" ] || fail "429 response has no Retry-After header"
pass "429 carries Retry-After ($RETRY_AFTER s)"

# --- error handling -------------------------------------------------------------
expect 400 "malformed JSON is 400, not 500" -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' -d '{not json'

echo
echo "Smoke test passed."
