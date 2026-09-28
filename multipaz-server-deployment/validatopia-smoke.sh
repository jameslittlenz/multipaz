#!/bin/sh
# Container smoke test for the Validatopia profile (docs/validatopia/PLAN.md's M3 completion bar:
# "Safe to deploy publicly", automated-tests section: "build the image, run it with
# BASE_URL=http://localhost:8000, then run a script that checks health, IACA fetch, admin login
# and the persona-issuance flow").
#
# Usage:
#   ./gradlew :multipaz-server-deployment:buildDockerImage && ./multipaz-server-deployment/validatopia-smoke.sh
#
# Requires: docker or podman, curl, python3 (used only to compute a TOTP code from the bootstrap
# account's enrollment secret — see compute_totp() below).
#
# What this does NOT cover: a full OpenID4VCI wallet round-trip (client attestation, DPoP, key
# binding) needed to actually mint a Photo ID from a persona — that's PhotoIdEndToEndTest.kt's job
# and needs a JVM, not a shell script. This script instead confirms, from the outside, that the
# seeded personas are visible through the authenticated admin API, which is as far as a black-box
# HTTP smoke test can practically go.

set -eu

CONTAINER_TOOL="${CONTAINER_TOOL:-}"
if [ -z "$CONTAINER_TOOL" ]; then
    if command -v podman >/dev/null 2>&1; then
        CONTAINER_TOOL=podman
    else
        CONTAINER_TOOL=docker
    fi
fi

IMAGE="${IMAGE:-multipaz/server-bundle:latest}"
CONTAINER_NAME="validatopia-smoke-$$"
BASE_URL="http://localhost:8000"
ADMIN_USER="admin"
ADMIN_PASS="smoke-test-password-$$"

fail() {
    echo "FAIL: $1" >&2
    cleanup
    exit 1
}

cleanup() {
    "$CONTAINER_TOOL" rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting container ($CONTAINER_TOOL) from $IMAGE..."
"$CONTAINER_TOOL" run -d --rm --name "$CONTAINER_NAME" \
    -p 8000:8000 \
    -e "BASE_URL=$BASE_URL" \
    -e "PROFILE=validatopia" \
    -e "ADMIN_BOOTSTRAP_USER=$ADMIN_USER" \
    -e "ADMIN_BOOTSTRAP_PASS=$ADMIN_PASS" \
    "$IMAGE" >/dev/null

echo "Waiting for health check..."
i=0
until curl -sf "$BASE_URL/health" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -ge 60 ]; then
        "$CONTAINER_TOOL" logs "$CONTAINER_NAME" || true
        fail "server did not become healthy within 60s"
    fi
    sleep 1
done
echo "OK: health check"

# /health is answered directly by nginx, which starts immediately — it says nothing about
# whether the openid4vci JVM behind it (started separately, in the background, by
# start-servers.sh) has finished starting up yet. Everything nginx actually proxies needs its
# own wait/retry rather than assuming the health check covers it too.
echo "Waiting for the openid4vci service behind nginx..."
i=0
until curl -sf "$BASE_URL/openid4vci/ca/credential_signing" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -ge 60 ]; then
        "$CONTAINER_TOOL" logs "$CONTAINER_NAME" || true
        fail "openid4vci did not become reachable within 60s of a healthy nginx"
    fi
    sleep 1
done

# --- IACA fetch ---
iaca=$(curl -sf "$BASE_URL/openid4vci/ca/credential_signing") || fail "IACA fetch failed"
case "$iaca" in
    *"BEGIN CERTIFICATE"*) echo "OK: IACA fetch" ;;
    *) fail "IACA response did not look like a PEM certificate" ;;
esac

# --- Admin login, step 1: password ---
login_response=$(curl -sf -X POST "$BASE_URL/openid4vci/admin_login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}") \
    || fail "admin_login request failed"
case "$login_response" in
    *'"status":"totp_enrollment_required"'*) echo "OK: admin_login (password accepted, TOTP enrollment required as expected)" ;;
    *) fail "unexpected admin_login response: $login_response" ;;
esac
secret=$(printf '%s' "$login_response" | sed -n 's/.*"secret":"\([^"]*\)".*/\1/p')
[ -n "$secret" ] || fail "no TOTP secret in admin_login response"

# --- Admin login, step 2: TOTP ---
compute_totp() {
    # RFC 6238 TOTP over HMAC-SHA1, matching Totp.kt server-side. Python's stdlib has everything
    # needed (base64.b32decode, hmac, hashlib) without extra dependencies.
    python3 - "$1" <<'PYEOF'
import base64, hmac, hashlib, struct, sys, time

secret = sys.argv[1]
padded = secret + "=" * (-len(secret) % 8)
key = base64.b32decode(padded, casefold=True)
counter = int(time.time()) // 30
msg = struct.pack(">Q", counter)
digest = hmac.new(key, msg, hashlib.sha1).digest()
offset = digest[-1] & 0x0f
code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7fffffff) % 1_000_000
print(f"{code:06d}")
PYEOF
}
code=$(compute_totp "$secret") || fail "could not compute TOTP code (is python3 installed?)"

totp_response=$(curl -sf -i -X POST "$BASE_URL/openid4vci/admin_login_totp" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ADMIN_USER\",\"code\":\"$code\"}") \
    || fail "admin_login_totp request failed"
case "$totp_response" in
    *'"status":"ok"'*) echo "OK: admin_login_totp (session established)" ;;
    *) fail "unexpected admin_login_totp response: $totp_response" ;;
esac
session_cookie=$(printf '%s' "$totp_response" | tr -d '\r' | sed -n 's/^[Ss]et-[Cc]ookie: \(validatopia_admin_session=[^;]*\).*/\1/p' | head -n1)
csrf_token=$(printf '%s' "$totp_response" | sed -n 's/.*"csrf_token":"\([^"]*\)".*/\1/p')
[ -n "$session_cookie" ] || fail "no session cookie in admin_login_totp response"
[ -n "$csrf_token" ] || fail "no csrf_token in admin_login_totp response"

# --- Persona-issuance flow: the seeded placeholder personas should be visible via the admin API ---
personas_response=$(curl -sf "$BASE_URL/openid4vci/admin_personas" -H "Cookie: $session_cookie") \
    || fail "admin_personas request failed"
case "$personas_response" in
    *'"id":"p1"'*) echo "OK: seeded persona 'p1' is visible" ;;
    *) fail "seeded persona 'p1' not found in admin_personas response: $personas_response" ;;
esac

echo ""
echo "All Validatopia smoke checks passed."
