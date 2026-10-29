#!/usr/bin/env bash
# Deletes the throwaway account (login, DELETE /me) and verifies login then returns 401.
# Only HTTP codes are printed; the password and tokens never are. Exit 1 if not confirmed deleted.
set -uo pipefail
API="${API:?}"
[ -f "$1" ] || { echo "no account file: nothing to clean"; exit 0; }
B="$(mktemp)"; trap 'rm -f "$B" "$1"' EXIT
login() { jq -nc --rawfile s "$1" '($s|split("\n")) as $l | {email:$l[0],password:$l[1]}' | curl -sS -o "$B" -w '%{http_code}' --max-time 120 -X POST "$API/auth/login" -H 'Content-Type: application/json' --data-binary @- || echo 000; }
c="$(login "$1")"; echo "login before delete -> $c"
if [ "$c" = 200 ]; then
  tok="$(jq -r '[.. | objects | .accessToken? // empty][0] // empty' "$B")"; : > "$B"
  d="$(jq -nc --rawfile s "$1" '($s|split("\n")) as $l | {password:$l[1]}' | curl -sS -o /dev/null -w '%{http_code}' --max-time 120 -X DELETE "$API/me" \
       -H 'Content-Type: application/json' -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" -H @<(printf 'Authorization: Bearer %s\n' "$tok") --data-binary @- || echo 000)"
  unset tok; echo "DELETE /me -> $d"
fi
c="$(login "$1")"
if [ "$c" = 401 ]; then echo "CLEANUP account deleted or never created (login 401)"; exit 0; fi
echo "CLEANUP NOT confirmed (login $c)"; exit 1
