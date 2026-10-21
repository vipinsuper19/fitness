#!/usr/bin/env bash
# Waits until the Render service has restarted after a given moment, so the
# smoke test hits the new deploy rather than the old instance.
#
# A restart is detected when GET /health reports uptimeSeconds lower than the
# seconds elapsed since SINCE_EPOCH (the push time, else job start).
# If no restart is seen within WAIT_SECONDS, this FAILS clearly (exit 1):
# a smoke pass against the old build would be a false green, and a missing
# restart usually means the Render build/deploy failed or was skipped.
set -euo pipefail
BASE_URL="${BASE_URL:-https://fitness-api-6pcc.onrender.com}"; BASE_URL="${BASE_URL%/}"
SINCE_EPOCH="${SINCE_EPOCH:-$(date +%s)}"
WAIT_SECONDS="${WAIT_SECONDS:-360}"
INTERVAL="${INTERVAL:-15}"
deadline=$(( $(date +%s) + WAIT_SECONDS ))

uptime_now() { curl -sS --max-time 30 "$BASE_URL/health" 2>/dev/null | jq -r '.uptimeSeconds // empty' 2>/dev/null || true; }

baseline="$(uptime_now)"
echo "baseline uptimeSeconds=${baseline:-unreachable}; waiting up to ${WAIT_SECONDS}s for a restart after $(date -u -d "@$SINCE_EPOCH" +%FT%TZ)"
while :; do
  now=$(date +%s); elapsed=$(( now - SINCE_EPOCH )); up="$(uptime_now)"
  if [[ "$up" =~ ^[0-9]+$ ]] && (( up < elapsed )); then
    echo "restart detected: uptimeSeconds=$up < ${elapsed}s since push"; exit 0
  fi
  echo "  uptime=${up:-unreachable} elapsed=${elapsed}s, no new deploy yet"
  (( now + INTERVAL <= deadline )) || break
  sleep "$INTERVAL"
done
echo "::error::No Render restart seen within ${WAIT_SECONDS}s of the push. Check the deploy in the Render dashboard; not running the smoke test against the old build."
exit 1
