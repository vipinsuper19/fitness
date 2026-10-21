#!/usr/bin/env bash
# Post-deploy smoke test: health+DB, register -> delete -> login must fail.
# Never prints tokens, passwords or token-bearing bodies. 3 auth requests on
# the happy path (+ at most 2 in cleanup), well under the auth rate limit.
set -euo pipefail
BASE_URL="${BASE_URL:-https://fitness-api-6pcc.onrender.com}"
BASE_URL="${BASE_URL%/}"
API="$BASE_URL/api/v1"
TMP="$(mktemp -d)"; chmod 700 "$TMP"
gh_mask() { [ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$1" || true; }

# State file lets a separate `if: always()` step clean up if this process is
# killed (e.g. cancelled job) before its own trap runs. Mode 600, runner temp.
STATE="${SMOKE_STATE:-}"

if [ "${1:-}" = cleanup ]; then
  [ -n "$STATE" ] && [ -f "$STATE" ] || { echo "cleanup: nothing to do"; exit 0; }
  EMAIL="$(sed -n 1p "$STATE")"; PASSWORD="$(sed -n 2p "$STATE")"; gh_mask "$PASSWORD"
  TOKEN=""; CREATED=1; DELETED=0
fi

if [ "${1:-}" != cleanup ]; then
EMAIL="smoke+${GITHUB_RUN_ID:-local}-$(openssl rand -hex 4)@example.invalid"
PASSWORD="Sm0ke-$(openssl rand -hex 16)"
gh_mask "$PASSWORD"
TOKEN=""; CREATED=0; DELETED=0
fi

req() { # method path body(json|"") token(""|t) -> sets CODE, body in $TMP/body
  local args=(-sS -o "$TMP/body" -w '%{http_code}' --max-time 60 -X "$1" "$API$2" -H 'Content-Type: application/json')
  [ -n "${4:-}" ] && args+=(-H "Authorization: Bearer $4")
  [ -n "${3:-}" ] && args+=(--data-binary @-)
  CODE="$(printf "%s" "${3:-}" | curl "${args[@]}" || true)"
}
body_json() { jq -n --arg e "$EMAIL" --arg p "$PASSWORD" "$1"; }
extract_token() { TOKEN="$(jq -r '[.. | objects | .accessToken? // empty][0] // empty' "$TMP/body" 2>/dev/null || true)"; [ -n "$TOKEN" ] && gh_mask "$TOKEN"; rm -f "$TMP/body"; }

cleanup() {
  local rc=$?
  if [ "$CREATED" = 1 ] && [ "$DELETED" = 0 ]; then
    echo "cleanup: deleting smoke user"
    if [ -z "$TOKEN" ]; then req POST /auth/login "$(body_json '{email:$e,password:$p}')"; if [ "$CODE" = 200 ]; then extract_token; elif [ "$CODE" = 401 ]; then echo "cleanup: login 401, account already gone"; DELETED=1; fi; fi
    if [ "$DELETED" = 1 ]; then :
    elif [ -n "$TOKEN" ]; then req DELETE /me "$(body_json '{password:$p}')" "$TOKEN"; echo "cleanup: DELETE /me -> $CODE"; if [ "$CODE" = 204 ]; then DELETED=1; else rc=1; fi
    else echo "::error::cleanup could not log in; smoke user may remain: $EMAIL"; rc=1; fi
  fi
  if [ -n "$STATE" ] && { [ "$DELETED" = 1 ] || [ "$CREATED" = 0 ]; }; then rm -f "$STATE"; fi
  rm -rf "$TMP"; exit $rc
}
trap cleanup EXIT
if [ "${1:-}" = cleanup ]; then exit 0; fi
[ -n "$STATE" ] && { (umask 077; printf '%s\n%s\n' "$EMAIL" "$PASSWORD" > "$STATE"); }

echo "1) health ($BASE_URL/health?db=true), up to ~3 min for cold start"
ok=0
for i in $(seq 1 18); do
  code="$(curl -sS -o "$TMP/h" -w '%{http_code}' --max-time 30 "$BASE_URL/health?db=true" || true)"
  if [ "$code" = 200 ] && jq -e '.status=="ok" and .db=="up"' "$TMP/h" >/dev/null 2>&1; then ok=1; break; fi
  echo "  attempt $i: HTTP $code, waiting"; sleep 10
done
[ "$ok" = 1 ] || { echo "::error::health check failed"; cat "$TMP/h" 2>/dev/null || true; exit 1; }
echo "  ok: $(jq -c . "$TMP/h")"

echo "2) register $EMAIL"
req POST /auth/register "$(body_json '{email:$e,password:$p,displayName:"Smoke Test"}')" "" 
[ "$CODE" = 201 ] || { echo "::error::register -> $CODE"; jq -c 'del(..|.accessToken?,.refreshToken?)' "$TMP/body" 2>/dev/null || true; exit 1; }
CREATED=1; extract_token
[ -n "$TOKEN" ] || { echo "::error::no accessToken in register response"; exit 1; }
echo "  register -> 201"

echo "3) delete account"
req DELETE /me "$(body_json '{password:$p}')" "$TOKEN"
[ "$CODE" = 204 ] || { echo "::error::DELETE /me -> $CODE"; exit 1; }
DELETED=1; echo "  DELETE /me -> 204"

echo "4) login must now fail"
req POST /auth/login "$(body_json '{email:$e,password:$p}')"
rm -f "$TMP/body"
[ "$CODE" = 401 ] || { echo "::error::login after delete -> $CODE (expected 401)"; exit 1; }
echo "  login -> 401 (account gone)"
echo "SMOKE PASSED"
