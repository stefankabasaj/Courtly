#!/usr/bin/env bash
# End-to-end smoke test: starts the real app + PostgreSQL with docker compose,
# so Flyway migrations run against a real database, then checks the main flows
# over HTTP.
#
# Usage (from the Courtly/ folder):  ./scripts/smoke-test.sh
# Needs: docker (with compose), curl, jq, openssl.
#
# Runs as its own compose project with throwaway credentials, so it never
# touches your local stack's database. It does need ports 8080, 5432, 6379,
# 9094 and 8025 free.
#
# Requests go through nginx to two app replicas, so everything here also
# checks that the instances agree with each other (shared Redis, same JWT key).

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
# long enough for the steps before the expiry check (mail waits included),
# short enough not to drag the test out
export BOOKING_PAYMENT_TIMEOUT=60s
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

MAILPIT="http://localhost:8025/api/v1"

# mail_count <to> <subject prefix> - emails Mailpit caught for that address
mail_count() {
    curl -s --get --data-urlencode "query=to:$1" "$MAILPIT/search" \
        | jq --arg s "$2" '[.messages[] | select(.Subject | startswith($s))] | length'
}

# wait_for_mail <to> <subject prefix> <description>
# events go API -> outbox -> Kafka -> notification service -> Mailpit, so give it a moment
wait_for_mail() {
    for i in $(seq 1 30); do
        [ "$(mail_count "$1" "$2")" -ge 1 ] && { pass "$3"; return; }
        sleep 1
    done
    fail "$3: no email \"$2\" to $1 within 30s"
}

kafka_cli() {
    local tool=$1; shift
    "${COMPOSE[@]}" exec -T kafka "/opt/kafka/bin/$tool" --bootstrap-server localhost:9092 "$@"
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

# --- startup ----------------------------------------------------------------------
# capture first - grep -q exiting early would trip pipefail
APP_LOGS=$("${COMPOSE[@]}" logs app)
grep -q "timezone Europe/Belgrade" <<<"$APP_LOGS" \
    || fail "app is not running in Europe/Belgrade (booking times would be off by 1-2h)"
pass "app runs in the courts' time zone"

[ "$(psql_smoke -tAc "SELECT count(*) FROM courtly.flyway_schema_history WHERE success" )" -ge 4 ] \
    || fail "not all migrations ran"
[ "$(psql_smoke -tAc "SELECT count(*) FROM pg_indexes WHERE schemaname = 'courtly' AND indexname = 'idx_users_email'")" = "0" ] \
    || fail "V4 did not drop idx_users_email"
pass "all migrations applied, V4 dropped the redundant index"

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
COURT_NAME=$(jq -r '.[0].name' <<<"$RESPONSE")
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
# how the emails write the day, e.g. "Wed 4 Nov"
SDAY=$(LC_ALL=C date -d "$DAY" '+%a %-d %b')
book "book 10-12" 201 10 12
BOOKING_ID=$(jq -r .id <<<"$RESPONSE")
wait_for_mail "$EMAIL" "Booking received: $COURT_NAME, $SDAY 10:00" "booking email arrives"
[ "$(jq -r .totalPrice <<<"$RESPONSE")" != "null" ] || fail "booking has no price"

book "overlapping 11-13 is rejected" 409 11 13
book "back-to-back 12-14 is allowed" 201 12 14
BACK_TO_BACK_ID=$(jq -r .id <<<"$RESPONSE")
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

expect 200 "admin cancels a booking with PATCH" -X PATCH "$BASE/bookings/$BACK_TO_BACK_ID/admin-cancel" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d '{"reason":"court maintenance"}'
[ "$(jq -r .cancellationReason <<<"$RESPONSE")" = "court maintenance" ] || fail "admin reason not stored"
wait_for_mail "$EMAIL" "Booking cancelled: $COURT_NAME, $SDAY 12:00" "admin cancellation email arrives"

expect 403 "user can't admin-cancel" -X PATCH "$BASE/bookings/$BACK_TO_BACK_ID/admin-cancel" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"reason":"nope"}'

expect 200 "user cancels own pending booking" -X PATCH "$BASE/bookings/$BOOKING_ID/cancel" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"reason":"smoke test"}'
[ "$(jq -r .status <<<"$RESPONSE")" = "CANCELLED" ] || fail "booking not cancelled"
wait_for_mail "$EMAIL" "Booking cancelled: $COURT_NAME, $SDAY 10:00" "cancellation email arrives"

book "cancelled slot can be booked again" 201 10 12
REBOOKED_ID=$(jq -r .id <<<"$RESPONSE")

# the simulated gateway declines ~10% of payments; a declined one can be retried
for i in $(seq 1 10); do
    expect 200 "pay for the booking (attempt $i)" -X POST "$BASE/payments/$REBOOKED_ID" \
        -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
        -d '{"paymentMethod":"CREDIT_CARD"}'
    [ "$(jq -r .status <<<"$RESPONSE")" = "SUCCESS" ] && break
    [ "$i" -eq 10 ] && fail "payment declined 10 times in a row"
done
wait_for_mail "$EMAIL" "Booking confirmed: $COURT_NAME, $SDAY 10:00" "confirmation email arrives"

# --- events: outbox, duplicates, dead letters --------------------------------------
for i in $(seq 1 15); do
    UNSENT=$(psql_smoke -tAc "SELECT count(*) FROM courtly.outbox_events WHERE published_at IS NULL")
    [ "$UNSENT" = "0" ] && break
    [ "$i" -eq 15 ] && fail "$UNSENT outbox event(s) still unsent after 15s"
    sleep 1
done
pass "every outbox event was sent to Kafka"

# send the 12:00 booking's BOOKING_CREATED again, like after a crash between the
# Kafka ack and marking the row. the notification service has to drop it
RECEIVED_1200="Booking received: $COURT_NAME, $SDAY 12:00"
[ "$(mail_count "$EMAIL" "$RECEIVED_1200")" -eq 1 ] || fail "expected exactly one '$RECEIVED_1200' email before the replay"
psql_smoke -c "UPDATE courtly.outbox_events SET published_at = NULL
               WHERE aggregate_id = '$BACK_TO_BACK_ID' AND event_type = 'BOOKING_CREATED'" >/dev/null
for i in $(seq 1 15); do
    [ "$(psql_smoke -tAc "SELECT count(*) FROM courtly.outbox_events WHERE published_at IS NULL")" = "0" ] && break
    sleep 1
done
sleep 5
[ "$(mail_count "$EMAIL" "$RECEIVED_1200")" -eq 1 ] || fail "a replayed event was emailed twice"
pass "a replayed event is not emailed twice"

# a message that isn't even JSON: no retries, straight to the dead letter topic
echo 'this is not json' | kafka_cli kafka-console-producer.sh --topic courtly.booking-events >/dev/null
DLT=$(kafka_cli kafka-console-consumer.sh --topic courtly.booking-events.DLT \
        --from-beginning --max-messages 1 --timeout-ms 30000 2>/dev/null || true)
grep -q "this is not json" <<<"$DLT" || fail "broken message did not reach the DLT"
pass "a broken message ends up in the DLT"

# --- unpaid bookings expire ------------------------------------------------------
book "book 16-17 and leave it unpaid" 201 16 17
UNPAID_ID=$(jq -r .id <<<"$RESPONSE")

echo "Waiting for the ${BOOKING_PAYMENT_TIMEOUT} payment window to pass..."
for i in $(seq 1 60); do
    STATUS=$(curl -s "$BASE/bookings/$UNPAID_ID" -H "Authorization: Bearer $USER_TOKEN" | jq -r .status)
    [ "$STATUS" = "CANCELLED" ] && break
    [ "$i" -eq 60 ] && fail "unpaid booking still $STATUS after 120s"
    sleep 2
done
pass "unpaid booking was cancelled automatically"
# also shows the notification service kept going after the broken message
wait_for_mail "$EMAIL" "Booking released: $COURT_NAME, $SDAY 16:00" "expiry email arrives"

expect 200 "cancellation reason says why" "$BASE/bookings/$UNPAID_ID" \
    -H "Authorization: Bearer $USER_TOKEN"
[ "$(jq -r .cancellationReason <<<"$RESPONSE")" = "Not paid in time" ] || fail "unexpected cancellation reason"

expect 400 "expired booking can't be paid" -X POST "$BASE/payments/$UNPAID_ID" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"paymentMethod":"CREDIT_CARD"}'

book "the freed slot can be booked again" 201 16 17

# --- login rate limit -------------------------------------------------------------
# nginx alternates between the two replicas, so these attempts land on both of them.
# the 6th is only blocked if they share one counter (Redis)
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

# 40 Cyrillic letters: 40 characters but 80 bytes, over BCrypt's 72
LONG_PASSWORD=$(printf 'ш%.0s' $(seq 1 40))
expect 422 "password over 72 bytes is 422, not 500" -X POST "$BASE/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"cyrillic-$(date +%s)@courtly.test\",\"password\":\"$LONG_PASSWORD\"}"

expect 422 "page size over 100 is 422, not 500" "$BASE/bookings?size=500" \
    -H "Authorization: Bearer $ADMIN_TOKEN"

expect 405 "wrong HTTP method is 405, not 500" -X PUT "$BASE/bookings/my" \
    -H "Authorization: Bearer $USER_TOKEN"

# --- actuator ---------------------------------------------------------------------
expect 200 "actuator health is public" "http://localhost:8080/actuator/health"
[ "$(jq 'has("components")' <<<"$RESPONSE")" = "false" ] || fail "health details shown without a token"
expect 200 "actuator health for a regular user" "http://localhost:8080/actuator/health" \
    -H "Authorization: Bearer $USER_TOKEN"
[ "$(jq 'has("components")' <<<"$RESPONSE")" = "false" ] || fail "health details shown to a regular user"
expect 200 "actuator health for admin" "http://localhost:8080/actuator/health" \
    -H "Authorization: Bearer $ADMIN_TOKEN"
[ "$(jq -r '.components.db.status' <<<"$RESPONSE")" = "UP" ] || fail "admin should see the db status"
pass "health details only for admins"

# --- notification service ----------------------------------------------------------
NOTIFICATIONS=$("${COMPOSE[@]}" ps -q notifications)
for i in $(seq 1 20); do
    HEALTH=$(docker inspect --format '{{.State.Health.Status}}' "$NOTIFICATIONS")
    [ "$HEALTH" = "healthy" ] && break
    [ "$i" -eq 20 ] && fail "notification service health is '$HEALTH'"
    sleep 3
done
pass "notification service is healthy"

# --- load balancing -------------------------------------------------------------
UPSTREAMS=$("${COMPOSE[@]}" logs nginx | grep -oE -- '-> [0-9.]+:8080' | sort -u | wc -l)
[ "$UPSTREAMS" -eq 2 ] || fail "expected requests on 2 replicas, nginx used $UPSTREAMS"
pass "nginx spread the requests over both replicas"

# --- containers -------------------------------------------------------------------
# the Docker healthcheck (busybox wget inside the image) has to work on every replica
for container in $("${COMPOSE[@]}" ps -q app); do
    for i in $(seq 1 30); do
        HEALTH=$(docker inspect --format '{{.State.Health.Status}}' "$container")
        [ "$HEALTH" = "healthy" ] && break
        [ "$i" -eq 30 ] && fail "container $container health is '$HEALTH' after 90s"
        sleep 3
    done
done
pass "Docker healthcheck reports healthy on both replicas"

# exec in the ENTRYPOINT: java is PID 1 and shuts down on SIGTERM instead of being killed
STOP_START=$(date +%s)
"${COMPOSE[@]}" stop -t 30 app >/dev/null 2>&1
STOP_SECONDS=$(( $(date +%s) - STOP_START ))
[ "$STOP_SECONDS" -lt 15 ] || fail "app took ${STOP_SECONDS}s to stop - SIGTERM is not reaching java"
pass "app stops cleanly on SIGTERM (${STOP_SECONDS}s)"

# with the app gone, nginx has nobody to send requests to. a 502 here (and not a
# hang or a connection error) shows nginx itself keeps answering
expect 502 "nginx answers 502 when no replica is up" "$BASE/health"

echo
echo "Smoke test passed."
