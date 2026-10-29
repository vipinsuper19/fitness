#!/usr/bin/env bash
# QA API checks against the Fitness API server for session corrections (#63), plan management (#65),
# the nutrition summary (#70), strength progress (#72), macro targets (#85) and progression suggestions (#90), using TWO throwaway
# accounts (A = owner, B = other user).
#   scripts/prod-check-p1.sh              # the Fitness API server (only allowed host, see ALLOWED_API)
#   scripts/prod-check-p1.sh --self-test  # register-timeout and signal cleanup against a local stub
# Safety:
# - Host allowlist: refuses to run unless API is exactly https://fitness-api-6pcc.onrender.com/api/v1
#   (loopback is allowed only inside --self-test).
# - Credentials only in per-run mode-600 state files in $P1_STATE_DIR (default ~/.cache/p1-state, umask 077); password/token never in argv or
#   output; JSON built with jq.
# - Cleanup on EXIT, INT and TERM: every account registered so far (or possibly registered, after a
#   register timeout) is logged into and deleted (DELETE /me), then login must return 401.
# - Register timeout / 5xx: the account may exist, so log in with the same credentials and, if that
#   works, delete it at once; the run then stops.
# Light load: sequential requests with a small pause. Prints PASS/FAIL per check; exit 1 on any FAIL.
set -uo pipefail
ALLOWED_API="https://fitness-api-6pcc.onrender.com/api/v1"
if [ "${1:-}" = --self-test ]; then
  PORT=8798; STUB="$(mktemp)"; trap 'rm -f "$STUB"' EXIT; cat > "$STUB" <<'PY'
import http.server,json,sys,time,threading
acc={}; slow={'n':int(sys.argv[2])}; lock=threading.Lock()
class H(http.server.BaseHTTPRequestHandler):
    def log_message(s,*a): pass
    def out(s,code,o=None):
        s.send_response(code); s.end_headers()
        if o is not None: s.wfile.write(json.dumps(o).encode())
    def h(s):
        n=int(s.headers.get('Content-Length') or 0); b=json.loads(s.rfile.read(n) or b'{}')
        p=s.path; tok=(s.headers.get('Authorization') or '')[7:]
        if p=='/__accounts': return s.out(200,{"n":len(acc)})
        if p.endswith('/auth/register'):
            with lock: acc[b['email']]=b['password']; sl=slow['n']>0; slow['n']-=1
            if sl: time.sleep(4)          # created, but the client times out
            return s.out(201,{"tokens":{"accessToken":"t-"+b['email']}})
        if p.endswith('/auth/login'):
            ok=acc.get(b.get('email'))==b.get('password') and b.get('password')
            return s.out(200,{"tokens":{"accessToken":"t-"+b['email']}}) if ok else s.out(401,{"code":"UNAUTHENTICATED"})
        if s.command=='DELETE' and p.endswith('/me'):
            e=tok[2:]
            if acc.get(e)==b.get('password'): del acc[e]; return s.out(204)
            return s.out(401,{})
        return s.out(200,{})
    do_GET=do_POST=do_PUT=do_DELETE=h
http.server.ThreadingHTTPServer(('127.0.0.1',int(sys.argv[1])),H).serve_forever()
PY
  LOCAL="http://127.0.0.1:$PORT/api/v1"; rc=0
  left() { curl -s "http://127.0.0.1:$PORT/__accounts" | jq -r .n; }
  # 1) A's register times out (account created server-side): login + delete, run stops, 0 left.
  python3 "$STUB" "$PORT" 1 & SP=$!; sleep 1
  API="$LOCAL" P1_SELFTEST=1 REGISTER_MAX_TIME=2 bash "$0" > /tmp/p1-selftest-1.log 2>&1
  n="$(left)"; grep -q "register timed out" /tmp/p1-selftest-1.log && [ "$n" = 0 ] && echo "SELFTEST register-timeout: PASS (0 accounts left)" || { echo "SELFTEST register-timeout: FAIL ($n left)"; rc=1; }
  kill "$SP"; wait "$SP" 2>/dev/null
  # 2) TERM and INT after both signups: both accounts deleted.
  for sig in TERM INT; do
    python3 "$STUB" "$PORT" 0 & SP=$!; sleep 1
    # Background jobs of a script start with SIGINT ignored (and an ignored signal can't be trapped),
    # so restore the default disposition before exec to test INT the way a terminal Ctrl-C sends it.
    API="$LOCAL" P1_SELFTEST=1 P1_SELFTEST_HOLD=1 python3 -c 'import os,signal,sys; signal.signal(signal.SIGINT, signal.SIG_DFL); os.execvp("bash", ["bash", sys.argv[1]])' "$0" > "/tmp/p1-selftest-$sig.log" 2>&1 & RP=$!
    for _ in $(seq 50); do grep -q HOLDING "/tmp/p1-selftest-$sig.log" && break; sleep 0.2; done
    kill -s "$sig" "$RP"; wait "$RP" 2>/dev/null
    n="$(left)"; [ "$(grep -c 'deleted (login 401)' "/tmp/p1-selftest-$sig.log")" = 2 ] && [ "$n" = 0 ] && echo "SELFTEST $sig cleanup: PASS (0 accounts left)" || { echo "SELFTEST $sig cleanup: FAIL ($n left)"; rc=1; }
    kill "$SP"; wait "$SP" 2>/dev/null
  done
  # 3) Allowlist: any other host is refused before any request.
  API="https://example.com/api/v1" bash "$0" > /tmp/p1-selftest-host.log 2>&1; h=$?
  [ "$h" = 2 ] && grep -q "refusing" /tmp/p1-selftest-host.log && echo "SELFTEST allowlist: PASS" || { echo "SELFTEST allowlist: FAIL"; rc=1; }
  rm -f "$STUB"; exit $rc
fi
API="${API:-$ALLOWED_API}"; API="${API%/}"
if [ "$API" != "$ALLOWED_API" ] && ! { [ "${P1_SELFTEST:-}" = 1 ] && [[ "$API" =~ ^http://127\.0\.0\.1:[0-9]+/api/v1$ ]]; }; then
  echo "refusing to run against $API: only $ALLOWED_API is allowed" >&2; exit 2
fi
umask 077
B="$(mktemp -t p1.body.XXXXXXXX)"; BF="$(mktemp -t p1.req.XXXXXXXX)"; HF="$(mktemp -t p1.hdr.XXXXXXXX)"
declare -A ST TOK CREATED DELETED
ACCOUNTS=()   # every account we attempted to register, in order
TMPS=()       # extra per-run temp files, removed at cleanup
# Credential state files live outside /tmp so a crash or reboot mid-run cannot lose them: an
# undeleted account can then still be logged into and deleted by hand.
STATE_DIR="${P1_STATE_DIR:-$HOME/.cache/p1-state}"; mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR"
AS=""   # current account; "" = unauthenticated
PASS=0; FAIL=0; FAILS=()
uid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
now() { TZ=Asia/Kolkata date +%H:%M:%S; }
cred() { jq -nc --rawfile s "${ST[$1]}" '($s | split("\n")) as $l | $l[0] as $e | $l[1] as $p | '"$2"; }
# r METHOD PATH [BODY] [IDEMPOTENCY_KEY] [MAX_TIME] -> $C, body in $B
r() {
  sleep 0.3
  local a=(-sS -o "$B" -w '%{http_code}' --max-time "${5:-120}" -X "$1" "$API$2" -H 'Content-Type: application/json')
  if [ -n "$AS" ] && [ -n "${TOK[$AS]}" ]; then printf 'Authorization: Bearer %s\n' "${TOK[$AS]}" > "$HF"; a+=(-H "@$HF"); fi
  [ -n "${4:-}" ] && a+=(-H "Idempotency-Key: $4")
  if [ -n "${3:-}" ]; then printf '%s' "$3" > "$BF"; a+=(--data-binary "@$BF"); fi
  C="$(curl "${a[@]}" 2>/dev/null)" || C=000
  echo "  $(now) IST [${AS:--}] $1 $2 -> $C"
}
j() { jq -r "$1" "$B" 2>/dev/null; }
check() { # check NAME CONDITION-RESULT(0/1) DETAIL
  if [ "$2" = 0 ]; then PASS=$((PASS+1)); echo "PASS $1"; else FAIL=$((FAIL+1)); FAILS+=("$1"); echo "FAIL $1 -- $3"; fi
}
is() { [ "$C" = "$1" ] && echo 0 || echo 1; }
code_is() { [ "$C" = "$1" ] && [ "$(j .code)" = "$2" ] && echo 0 || echo 1; }
token_of() { TOK[$1]="$(j '[.. | objects | .accessToken? // empty][0] // empty')"; }
# Log in as $1 (if needed) and DELETE /me; sets DELETED[$1]=1 on 204.
delete_account() {
  local a=$1
  TOK[$a]=""; AS=""; r POST /auth/login "$(cred "$a" '{email:$e,password:$p}')"
  [ "$C" = 200 ] || return 0
  token_of "$a"; AS=$a
  r DELETE /me "$(cred "$a" '{password:$p}')" "$(uid)"; [ "$C" = 204 ] && DELETED[$a]=1
  AS=""
}
CLEANED=0
cleanup() {
  [ "$CLEANED" = 1 ] && return; CLEANED=1
  set +e; trap '' INT TERM
  for a in ${ACCOUNTS[@]+"${ACCOUNTS[@]}"}; do
    if [ "${CREATED[$a]}" = 1 ] && [ "${DELETED[$a]}" = 0 ]; then delete_account "$a"; fi
    TOK[$a]=""; AS=""; r POST /auth/login "$(cred "$a" '{email:$e,password:$p}')"
    if [ "$C" = 401 ]; then echo "CLEANUP account $a deleted (login 401)"; rm -f "${ST[$a]}"
    else echo "CLEANUP account $a NOT confirmed deleted (login $C); credentials kept in ${ST[$a]} (mode 600)"; fi
  done
  rm -f "$B" "$BF" "$HF" ${TMPS[@]+"${TMPS[@]}"}
  echo "SUMMARY pass=$PASS fail=$FAIL ${FAILS[*]:-}"
}
trap cleanup EXIT
trap 'echo "interrupted (INT)"; exit 130' INT
trap 'echo "terminated (TERM)"; exit 143' TERM

signup() {
  local a=$1
  ST[$a]="$(mktemp -p "$STATE_DIR" p1.state.XXXXXXXX)"
  { printf 'p1check-%s@example.invalid\n' "$(openssl rand -hex 5)"; printf 'Pr0be-'; openssl rand -hex 12; } > "${ST[$a]}"
  TOK[$a]=""; DELETED[$a]=0
  # Counted as (possibly) created before the request goes out, so any exit from here on cleans it up.
  CREATED[$a]=1; ACCOUNTS+=("$a")
  AS=""; r POST /auth/register "$(cred "$a" '{email:$e,password:$p,displayName:"QA"}')" "$(uid)" "${REGISTER_MAX_TIME:-120}"
  if [ "$C" = 201 ]; then token_of "$a"; return 0; fi
  if [ "$C" = 000 ] || [ "${C:0:1}" = 5 ]; then
    echo "register timed out or failed ($C) for $a; the account may exist: logging in to delete it"
    delete_account "$a"
    [ "${DELETED[$a]}" = 1 ] && echo "  account $a existed and was deleted" || echo "  login did not work: account $a was not created (verified again at cleanup)"
  else
    CREATED[$a]=0   # definite rejection (4xx): nothing was created
  fi
  echo "FAIL could not register throwaway account $a"; exit 1
}
echo "API $API (first call may take ~60-90 s: cold start)"
signup A; signup B
[ -n "${TOK[A]}" ] && [ -n "${TOK[B]}" ] || { echo "FAIL could not get tokens for throwaway accounts"; exit 1; }
if [ "${P1_SELFTEST:-}" = 1 ]; then
  if [ "${P1_SELFTEST_HOLD:-}" = 1 ]; then echo HOLDING; while :; do sleep 0.2; done; fi
  exit 0
fi
TODAY="$(TZ=Asia/Kolkata date +%F)"; YDAY="$(TZ=Asia/Kolkata date -d yesterday +%F)"; TMRW="$(TZ=Asia/Kolkata date -d tomorrow +%F)"
AS=A; r PUT /me/profile '{"timezone":"Asia/Kolkata"}' "$(uid)"   # best effort; ignored if not a field

echo "== Plan management (#65) =="
AS=A
r GET /workout-plans; check "PM01 list (new user) 200, empty" "$([ "$C" = 200 ] && [ "$(j '.items|length')" = 0 ] && echo 0 || echo 1)" "C=$C"
r GET '/workout-plans?status=bogus'; check "PM02 list bad status -> 400" "$(is 400)" "C=$C"
r POST /workout-plans/generate '{"goal":"maintain","experienceLevel":"beginner","daysPerWeek":3,"sessionMinutes":30}'
DRAFT="$(cat "$B")"
r POST /workout-plans "$DRAFT" "$(uid)"; PID="$(j .id)"; check "PM03 save plan 201" "$(is 201)" "C=$C"
r POST "/workout-plans/$PID/activate" '' "$(uid)"; check "PM04 activate (no body) 200 isActive" "$([ "$C" = 200 ] && [ "$(j .isActive)" = true ] && echo 0 || echo 1)" "C=$C"
r GET '/workout-plans?status=active'; check "PM05 list status=active has plan, status active" "$([ "$(j "[.items[]|select(.id==\"$PID\")|.status][0]")" = active ] && echo 0 || echo 1)" "$(j -c '.items|map({id,status})' 2>/dev/null)"
K="$(uid)"
r POST "/workout-plans/$PID/deactivate" '' "$K"; check "PM06 pause (deactivate) 204" "$(is 204)" "C=$C"
r POST "/workout-plans/$PID/deactivate" '' "$K"; check "PM07 pause same-key replay 204" "$(is 204)" "C=$C"
r GET /workout-plans/active; check "PM08 GET /active after pause -> 204" "$(is 204)" "C=$C"
r GET '/workout-plans?status=paused'; P1="$(j "[.items[]|select(.id==\"$PID\")][0].pausedAt")"
check "PM09 paused plan listed with pausedAt, isActive false, currentWeek null" "$([ -n "$P1" ] && [ "$P1" != null ] && [ "$(j "[.items[]|select(.id==\"$PID\")][0].isActive")" = false ] && [ "$(j "[.items[]|select(.id==\"$PID\")][0].currentWeek")" = null ] && echo 0 || echo 1)" "$(j -c '.items' | head -c 300)"
sleep 1
r POST "/workout-plans/$PID/deactivate" '' "$(uid)"; r GET '/workout-plans?status=paused'
check "PM10 pause again (new key) 204, pausedAt kept" "$([ "$(j "[.items[]|select(.id==\"$PID\")][0].pausedAt")" = "$P1" ] && echo 0 || echo 1)" "pausedAt now $(j "[.items[]|select(.id==\"$PID\")][0].pausedAt") was $P1"
r POST "/workout-plans/$PID/activate" '{"resume":"yes"}' "$(uid)"; check "PM11 activate bad resume type -> 400" "$(is 400)" "C=$C"
r POST "/workout-plans/$PID/activate" '{"resume":true}' "$(uid)"; check "PM12 resume 200 isActive currentWeek 1" "$([ "$C" = 200 ] && [ "$(j .isActive)" = true ] && [ "$(j .currentWeek)" = 1 ] && echo 0 || echo 1)" "C=$C week=$(j .currentWeek)"
r GET "/workout-plans/$PID"; check "PM13 resumed plan pausedAt cleared" "$([ "$(j .pausedAt)" = null ] && echo 0 || echo 1)" "pausedAt=$(j .pausedAt)"
AS=B
r GET "/workout-plans/$PID"; check "PM14 other user GET plan -> 404" "$(is 404)" "C=$C"
r POST "/workout-plans/$PID/deactivate" '' "$(uid)"; check "PM15 other user pause -> 404" "$(is 404)" "C=$C"
r POST "/workout-plans/$PID/activate" '' "$(uid)"; check "PM16 other user activate -> 404" "$(is 404)" "C=$C"
r DELETE "/workout-plans/$PID" '' "$(uid)"; check "PM17 other user archive -> 404" "$(is 404)" "C=$C"
r GET '/workout-plans?status=archived'; check "PM18 other user list doesn't show A's plan" "$([ "$C" = 200 ] && ! grep -q "$PID" "$B" && echo 0 || echo 1)" "C=$C"
AS=A
r GET /workout-plans/active; check "PM19 A's plan still active after B's attempts" "$([ "$C" = 200 ] && [ "$(j .id)" = "$PID" ] && echo 0 || echo 1)" "C=$C"
r DELETE "/workout-plans/$PID" '' "$(uid)"; check "PM20 archive active plan 204" "$(is 204)" "C=$C"
r GET /workout-plans/active; check "PM21 GET /active after archive -> 204" "$(is 204)" "C=$C"
r GET /workout-plans; check "PM22 default list hides archived" "$([ "$C" = 200 ] && ! grep -q "$PID" "$B" && echo 0 || echo 1)" "C=$C"
r GET '/workout-plans?status=archived'; A1="$(j "[.items[]|select(.id==\"$PID\")][0].archivedAt")"
check "PM23 status=archived lists it with archivedAt" "$([ -n "$A1" ] && [ "$A1" != null ] && echo 0 || echo 1)" "archivedAt=$A1"
sleep 1
r DELETE "/workout-plans/$PID" '' "$(uid)"; r GET '/workout-plans?status=archived'
check "PM24 archive again 204, archivedAt kept" "$([ "$(j "[.items[]|select(.id==\"$PID\")][0].archivedAt")" = "$A1" ] && echo 0 || echo 1)" "archivedAt $(j "[.items[]|select(.id==\"$PID\")][0].archivedAt")"
r POST "/workout-plans/$PID/activate" '' "$(uid)"; check "PM25 activate archived -> 409 PLAN_ARCHIVED" "$(code_is 409 PLAN_ARCHIVED)" "C=$C code=$(j .code)"
r GET "/workout-plans/$PID"; VER="$(j .version)"; PA0="$(j .pausedAt)"
UPD="$(printf '%s' "$DRAFT" | jq -c --argjson v "${VER:-1}" '. + {baseVersion:$v, name:(.name+" v2")}')"
r PATCH "/workout-plans/$PID" "$UPD" "$(uid)"; check "PM26 PATCH archived -> 409 PLAN_ARCHIVED" "$(code_is 409 PLAN_ARCHIVED)" "C=$C code=$(j .code) $(j .message)"
r POST "/workout-plans/$PID/deactivate" '' "$(uid)"; r GET "/workout-plans/$PID"
check "PM27 pause on archived: 204 no-op, pausedAt unchanged (archive of the active plan stamps it once)" "$([ "$(j .pausedAt)" = "$PA0" ] && echo 0 || echo 1)" "pausedAt=$(j .pausedAt) was $PA0"

echo "== Session corrections (#63) =="
AS=A
r GET '/exercises?limit=2'; EX="$(j '[.. | objects | select(has("id") and has("name")) | .id][0] // empty')"
r POST /workout-sessions '{}' "$(uid)"; SID="$(j .id)"
S1="$(uid)"; S2="$(uid)"
r POST "/workout-sessions/$SID/sets" "$(jq -nc --arg c "$S1" --arg x "$EX" '{clientSetId:$c,exerciseId:$x,setIndex:0,reps:10,completed:true}')" "$(uid)"
r POST "/workout-sessions/$SID/sets" "$(jq -nc --arg c "$S2" --arg x "$EX" '{clientSetId:$c,exerciseId:$x,setIndex:1,reps:10,completed:true}')" "$(uid)"
SET() { jq -nc --arg c "$1" --arg x "$EX" --argjson n "$2" '{clientSetId:$c,exerciseId:$x,setIndex:0,reps:$n,completed:true}'; }
r PUT "/workout-sessions/$SID/corrections/sets/$S1" "$(SET "$S1" 12)" "$(uid)"; check "SC01 correct before complete -> 409 SESSION_NOT_COMPLETED" "$(code_is 409 SESSION_NOT_COMPLETED)" "C=$C code=$(j .code)"
r POST "/workout-sessions/$SID/complete" '{"perceivedDifficulty":3}' "$(uid)"; XP0="$(j .xpBalance.total)"; check "SC02 complete 200" "$(is 200)" "C=$C"
r PUT "/workout-sessions/$SID/corrections/sets/$S1" "$(SET "$S1" 20)"; check "SC03 correction without Idempotency-Key -> 400" "$(is 400)" "C=$C"
r PUT "/workout-sessions/$SID/corrections/sets/$S1" "$(SET "$(uid)" 20)" "$(uid)"; check "SC04 body clientSetId != path -> 400" "$(is 400)" "C=$C"
K="$(uid)"
r PUT "/workout-sessions/$SID/corrections/sets/$S1" "$(SET "$S1" 20)" "$K"; FIRST="$(jq -cS . "$B")"; check "SC05 correct set (reps 10->20) 200 with xpDelta" "$([ "$C" = 200 ] && [ "$(j '.xpDelta|type')" = number ] && echo 0 || echo 1)" "C=$C $(j -c '{xpDelta,xpBalance}')"
XP1="$(j .xpBalance.total)"
r PUT "/workout-sessions/$SID/corrections/sets/$S1" "$(SET "$S1" 20)" "$K"; check "SC06 same-key replay -> same body" "$([ "$C" = 200 ] && [ "$(jq -cS . "$B")" = "$FIRST" ] && echo 0 || echo 1)" "C=$C"
r PUT "/workout-sessions/$SID/corrections/sets/$S1" "$(SET "$S1" 25)" "$K"; check "SC07 same key, different body -> 409 IDEMPOTENCY_KEY_REUSED" "$(code_is 409 IDEMPOTENCY_KEY_REUSED)" "C=$C code=$(j .code)"
r GET /xp/balance; check "SC08 /xp/balance matches correction result" "$([ "$(j .total)" = "$XP1" ] && echo 0 || echo 1)" "balance=$(j .total) result=$XP1 before=$XP0"
r GET "/workout-sessions/$SID"; check "SC09 corrected set edited in place (2 sets, reps 20)" "$([ "$(j '.sets|length')" = 2 ] && [ "$(j "[.sets[]|select(.clientSetId==\"$S1\")][0].reps")" = 20 ] && echo 0 || echo 1)" "$(j -c '[.sets[]|{clientSetId,reps}]')"
r DELETE "/workout-sessions/$SID/corrections/sets/$S2" '' "$(uid)"; check "SC10 delete set 200" "$(is 200)" "C=$C"
r DELETE "/workout-sessions/$SID/corrections/sets/$S2" '' "$(uid)"; check "SC11 delete again (new key) -> 404 SET_NOT_FOUND" "$(code_is 404 SET_NOT_FOUND)" "C=$C code=$(j .code)"
r DELETE "/workout-sessions/$SID/corrections/sets/$S1" '' "$(uid)"; check "SC12 delete last completed set -> 422 SESSION_EMPTY" "$(code_is 422 SESSION_EMPTY)" "C=$C code=$(j .code)"
r PATCH "/workout-sessions/$SID/completion" '{"perceivedDifficulty":4}' "$(uid)"; check "SC13 PATCH completion difficulty 200" "$([ "$C" = 200 ] && [ "$(j .session.perceivedDifficulty)" = 4 ] && echo 0 || echo 1)" "C=$C"
CA="$(j .session.completedAt)"; LATER="$(python3 -c "import datetime,sys;d=datetime.datetime.fromisoformat(sys.argv[1].replace('Z','+00:00'));print((d+datetime.timedelta(seconds=30)).isoformat().replace('+00:00','Z'))" "$CA" 2>/dev/null)"
r PATCH "/workout-sessions/$SID/completion" "$(jq -nc --arg t "$LATER" '{completedAt:$t}')" "$(uid)"; check "SC14 completedAt later than current -> 400" "$(is 400)" "C=$C"
r PATCH "/workout-sessions/$SID/completion" '{}' "$(uid)"; check "SC15 empty completion body -> 400" "$(is 400)" "C=$C"
AS=B
r PUT "/workout-sessions/$SID/corrections/sets/$S1" "$(SET "$S1" 99)" "$(uid)"; check "SC16 other user correct set -> 404" "$(is 404)" "C=$C"
r PATCH "/workout-sessions/$SID/completion" '{"perceivedDifficulty":1}' "$(uid)"; check "SC17 other user PATCH completion -> 404" "$(is 404)" "C=$C"
r DELETE "/workout-sessions/$SID/corrections/sets/$S1" '' "$(uid)"; check "SC18 other user delete set -> 404" "$(is 404)" "C=$C"
r GET /xp/balance; check "SC19 other user's XP untouched (0)" "$([ "$(j .total)" = 0 ] && echo 0 || echo 1)" "total=$(j .total)"
AS=""
r PUT "/workout-sessions/$SID/corrections/sets/$S1" "$(SET "$S1" 99)" "$(uid)"; check "SC20 unauthenticated -> 401" "$(is 401)" "C=$C"

echo "== Nutrition summary (#70) =="
AS=A
r GET "/nutrition/summary?from=$YDAY&to=$TMRW"; check "NS01 no logs: 200, 3 day points, totals/water null" "$([ "$C" = 200 ] && [ "$(j '.points|length')" = 3 ] && [ "$(j '[.points[]|select(.totals!=null or .waterMl!=null)]|length')" = 0 ] && echo 0 || echo 1)" "C=$C $(j -c '.points|map({period,totals,waterMl})' | head -c 300)"
r GET '/foods/search?query=roti'; FOOD="$(j '[.. | objects | select(has("id") and has("nutrients")) | .id][0] // empty')"
r POST /food-logs "$(jq -nc --arg f "$FOOD" --arg d "$TODAY" '{foodId:$f,date:$d,mealType:"lunch",servings:2}')" "$(uid)"; check "NS02 log food 201" "$(is 201)" "C=$C"
KCAL="$(j '.nutrients.energyKcal // .totals.energyKcal // empty')"
r POST /water-logs "$(jq -nc --arg d "$TODAY" '{date:$d,amountMl:250}')" "$(uid)"
r POST /water-logs "$(jq -nc --arg d "$TODAY" '{date:$d,amountMl:500}')" "$(uid)"
r GET "/nutrition/summary?from=$YDAY&to=$TMRW&bucket=day"
check "NS03 day point for today: daysLogged 1, waterMl 750, entryCount 1" "$([ "$(j ".points[]|select(.period==\"$TODAY\")|.daysLogged")" = 1 ] && [ "$(j ".points[]|select(.period==\"$TODAY\")|.waterMl")" = 750 ] && [ "$(j ".points[]|select(.period==\"$TODAY\")|.entryCount")" = 1 ] && echo 0 || echo 1)" "$(j -c ".points[]|select(.period==\"$TODAY\")" | head -c 400)"
check "NS04 yesterday stays null (missing != 0)" "$([ "$(j ".points[]|select(.period==\"$YDAY\")|.totals")" = null ] && echo 0 || echo 1)" "$(j -c ".points[]|select(.period==\"$YDAY\")|.totals")"
check "NS05 today's kcal equals the log's kcal" "$([ -z "$KCAL" ] || python3 -c "import sys;sys.exit(0 if abs(float(sys.argv[1])-float(sys.argv[2]))<0.01 else 1)" "$(j ".points[]|select(.period==\"$TODAY\")|.totals.energyKcal")" "$KCAL" && echo 0 || echo 1)" "summary=$(j ".points[]|select(.period==\"$TODAY\")|.totals.energyKcal") log=$KCAL"
WFROM="$(TZ=Asia/Kolkata date -d "$TODAY -13 days" +%F)"
r GET "/nutrition/summary?from=$WFROM&to=$TODAY&bucket=week"
check "NS06 week bucket: periods after the first are Mondays, days sum to 14" "$(python3 -c "
import json,sys,datetime;d=json.load(open(sys.argv[1]));p=d['points']
ok=all(datetime.date.fromisoformat(x['period']).weekday()==0 for x in p[1:]) and sum(x['daysInPeriod'] for x in p)==14 and p[0]['period']==sys.argv[2]
sys.exit(0 if ok else 1)" "$B" "$WFROM" && echo 0 || echo 1)" "$(j -c '.points|map({period,daysInPeriod})')"
r GET "/nutrition/summary?from=$TMRW&to=$YDAY"; check "NS07 from > to -> 400" "$(is 400)" "C=$C"
r GET "/nutrition/summary?from=2025-01-01&to=2026-01-03"; check "NS08 range > 366 days -> 400" "$(is 400)" "C=$C"
r GET "/nutrition/summary?from=$YDAY&to=$TODAY&bucket=year"; check "NS09 bad bucket -> 400" "$(is 400)" "C=$C"
r GET "/nutrition/summary?to=$TODAY"; check "NS10 missing from -> 400" "$(is 400)" "C=$C"
AS=B
r GET "/nutrition/summary?from=$YDAY&to=$TMRW"; check "NS11 other user sees none of A's logs" "$([ "$C" = 200 ] && [ "$(j '[.points[]|select(.totals!=null or .waterMl!=null)]|length')" = 0 ] && echo 0 || echo 1)" "C=$C"
AS=""
r GET "/nutrition/summary?from=$YDAY&to=$TODAY"; check "NS12 unauthenticated -> 401" "$(is 401)" "C=$C"

echo "== Strength progress (#72) =="
AS=A
r GET '/exercises?limit=5'; mapfile -t EXS < <(j '[.. | objects | select(has("id") and has("name")) | .id] | unique_by(.) | .[]' )
EX2=""; EX3=""; for x in "${EXS[@]}"; do [ "$x" = "$EX" ] && continue; [ -z "$EX2" ] && { EX2=$x; continue; }; [ -z "$EX3" ] && EX3=$x; done
r GET /xp/balance; XPS="$(j .total)"
r POST /workout-sessions '{}' "$(uid)"; SID2="$(j .id)"; W1="$(uid)"; W2="$(uid)"
WSET() { jq -nc --arg c "$1" --arg x "$EX2" --argjson i "$2" --argjson w "$3" --argjson n "$4" '{clientSetId:$c,exerciseId:$x,setIndex:$i,reps:$n,weightKg:$w,completed:true}'; }
r POST "/workout-sessions/$SID2/sets" "$(WSET "$W1" 0 100 5)" "$(uid)"
r POST "/workout-sessions/$SID2/sets" "$(WSET "$W2" 1 80 13)" "$(uid)"
r POST "/workout-sessions/$SID2/complete" '{"perceivedDifficulty":3}' "$(uid)"; check "ST00 setup: weighted session (100x5, 80x13) completed" "$(is 200)" "C=$C"
r GET /xp/balance; XPS="$(j .total)"
pt() { j ".points[]|select(.period==\"$TODAY\")|$1"; }
r GET "/progress/exercises?from=$YDAY&to=$TMRW"
check "ST01 list 200: both exercises performed today, timezone echoed" "$([ "$C" = 200 ] && [ "$(j '.items|length')" = 2 ] && [ "$(j .timezone)" != null ] && echo 0 || echo 1)" "C=$C $(j -c '[.items[]|{exerciseId,sessions}]' | head -c 300)"
check "ST02 list order lastPerformedAt DESC (weighted session first)" "$([ "$(j '.items[0].exerciseId')" = "$EX2" ] && echo 0 || echo 1)" "first=$(j '.items[0].exerciseId')"
check "ST03 list pr all-time: maxWeightKg 100, maxReps 13, best1RmKg 116.7" "$(L=".items[]|select(.exerciseId==\"$EX2\")|.pr"; [ "$(j "$L.maxWeightKg")" = 100 ] && [ "$(j "$L.maxReps")" = 13 ] && [ "$(j "$L.best1RmKg")" = 116.7 ] && echo 0 || echo 1)" "$(j -c ".items[]|select(.exerciseId==\"$EX2\")|.pr")"
r GET "/progress/exercises?from=$YDAY&to=$TMRW&limit=1"; CUR="$(j .nextCursor)"; FIRST1="$(j '.items[0].exerciseId')"
check "ST04 limit=1 gives 1 item + nextCursor" "$([ "$(j '.items|length')" = 1 ] && [ -n "$CUR" ] && [ "$CUR" != null ] && echo 0 || echo 1)" "cursor=$CUR"
r GET "/progress/exercises?from=$YDAY&to=$TMRW&limit=1&cursor=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "$CUR")"
check "ST05 page 2 = the other exercise, then no cursor" "$([ "$C" = 200 ] && [ "$(j '.items[0].exerciseId')" != "$FIRST1" ] && [ "$(j '.items|length')" = 1 ] && [ "$(j .nextCursor)" = null ] && echo 0 || echo 1)" "C=$C $(j -c '{items:[.items[].exerciseId],nextCursor}')"
r GET "/progress/exercises/$EX2?from=$YDAY&to=$TMRW"
check "ST06 detail day: 3 points, default bucket day, name/trackingType present" "$([ "$C" = 200 ] && [ "$(j '.points|length')" = 3 ] && [ "$(j .bucket)" = day ] && [ "$(j .name)" != null ] && [ "$(j .trackingType)" != null ] && echo 0 || echo 1)" "C=$C"
check "ST07 today: sessions 1, sets 2, volume 1540, reps 18, top 100, 1RM 116.7 (13-rep set no 1RM)" "$([ "$(pt .sessions)" = 1 ] && [ "$(pt .sets)" = 2 ] && python3 -c "import sys;sys.exit(0 if float(sys.argv[1])==1540 else 1)" "$(pt .volumeKg)" && [ "$(pt .totalReps)" = 18 ] && [ "$(pt .topSetWeightKg)" = 100 ] && [ "$(pt .best1RmKg)" = 116.7 ] && echo 0 || echo 1)" "$(j -c ".points[]|select(.period==\"$TODAY\")")"
check "ST08 empty day: sessions 0, sets 0, volume/reps null; duration/distance null for weighted" "$(Y=".points[]|select(.period==\"$YDAY\")"; [ "$(j "$Y.sessions")" = 0 ] && [ "$(j "$Y.sets")" = 0 ] && [ "$(j "$Y.volumeKg")" = null ] && [ "$(j "$Y.totalReps")" = null ] && [ "$(pt .totalDurationSec)" = null ] && [ "$(pt .maxSetDistanceM)" = null ] && echo 0 || echo 1)" "$(j -c ".points[]|select(.period==\"$YDAY\")")"
r GET "/progress/exercises/$EX2?from=$WFROM&to=$TODAY&bucket=week"
check "ST09 week bucket: Mondays after the first, first clipped to from" "$(python3 -c "
import json,sys,datetime;p=json.load(open(sys.argv[1]))['points']
sys.exit(0 if p and p[0]['period']==sys.argv[2] and all(datetime.date.fromisoformat(x['period']).weekday()==0 for x in p[1:]) else 1)" "$B" "$WFROM" && echo 0 || echo 1)" "$(j -c '[.points[].period]')"
r GET "/progress/exercises/$EX2?from=2025-01-01&to=2025-01-31"
check "ST10 PR outside the range still shown (all-time)" "$([ "$C" = 200 ] && [ "$(j .pr.maxWeightKg)" = 100 ] && [ "$(j '[.points[].sessions]|add')" = 0 ] && echo 0 || echo 1)" "C=$C pr=$(j -c .pr)"
r GET "/progress/exercises/$EX3?from=$YDAY&to=$TMRW"
check "ST11 never-performed exercise: 200, all-null pr" "$([ "$C" = 200 ] && [ "$(j '[.pr|to_entries[]|select(.value!=null)]|length')" = 0 ] && echo 0 || echo 1)" "C=$C pr=$(j -c .pr)"
r GET "/progress/exercises/00000000-0000-4000-8000-000000000000?from=$YDAY&to=$TODAY"; check "ST12 unknown exercise -> 404" "$(is 404)" "C=$C"
r GET "/progress/exercises/not-a-uuid?from=$YDAY&to=$TODAY"; check "ST13 malformed exercise id -> 404" "$(is 404)" "C=$C"
r GET "/progress/exercises?from=$TMRW&to=$YDAY"; check "ST14 list from > to -> 400" "$(is 400)" "C=$C"
r GET "/progress/exercises?to=$TODAY"; check "ST15 list missing from -> 400" "$(is 400)" "C=$C"
r GET "/progress/exercises?from=$YDAY&to=$TODAY&cursor=garbage"; check "ST16 list bad cursor -> 400" "$(is 400)" "C=$C"
r GET "/progress/exercises/$EX2?from=2025-01-01&to=2026-01-02&bucket=month"; check "ST17 detail 367 days with bucket=month -> 400" "$(is 400)" "C=$C"
r GET "/progress/exercises/$EX2?from=2025-01-01&to=2026-01-01&bucket=month"; check "ST18 detail 366 days with bucket=month -> 200" "$(is 200)" "C=$C"
r GET "/progress/exercises/$EX2?from=$YDAY&to=$TODAY&bucket=year"; check "ST19 detail bad bucket -> 400" "$(is 400)" "C=$C"
r GET "/progress/exercises/$EX2?from=2026-02-30&to=$TODAY"; check "ST20 detail bad date -> 400" "$(is 400)" "C=$C"
r PUT "/workout-sessions/$SID2/corrections/sets/$W1" "$(WSET "$W1" 0 50 5)" "$(uid)"; XPS="$(j .xpBalance.total)"
r GET "/progress/exercises/$EX2?from=$YDAY&to=$TMRW"
check "ST21 correction 100->50 kg lowers the record to 80 (recomputed)" "$([ "$(j .pr.maxWeightKg)" = 80 ] && [ "$(pt .topSetWeightKg)" = 80 ] && echo 0 || echo 1)" "pr=$(j -c .pr) top=$(pt .topSetWeightKg)"
r GET /xp/balance; check "ST22 progress reads don't change XP" "$([ "$(j .total)" = "$XPS" ] && echo 0 || echo 1)" "now=$(j .total) expected=$XPS"
AS=B
r GET "/progress/exercises?from=$YDAY&to=$TMRW"; check "ST23 other user list: 200, no items" "$([ "$C" = 200 ] && [ "$(j '.items|length')" = 0 ] && echo 0 || echo 1)" "C=$C $(j -c '.items|map(.exerciseId)')"
r GET "/progress/exercises/$EX2?from=$YDAY&to=$TMRW"
check "ST24 other user detail of same exercise: 0 sessions, null pr (no leak of A's sets)" "$([ "$C" = 200 ] && [ "$(j '[.points[].sessions]|add')" = 0 ] && [ "$(j '[.pr|to_entries[]|select(.value!=null)]|length')" = 0 ] && echo 0 || echo 1)" "C=$C pr=$(j -c .pr)"
AS=""
r GET "/progress/exercises?from=$YDAY&to=$TODAY"; check "ST25 list unauthenticated -> 401" "$(is 401)" "C=$C"
r GET "/progress/exercises/$EX2?from=$YDAY&to=$TODAY"; check "ST26 detail unauthenticated -> 401" "$(is 401)" "C=$C"

echo "== Macro targets (#85) =="
AS=B
r GET /me/profile; check "MT01 new profile: macro targets null" "$([ "$C" = 200 ] && [ "$(j '[.dailyProteinTargetG,.dailyCarbsTargetG,.dailyFatTargetG]|map(select(.!=null))|length')" = 0 ] && echo 0 || echo 1)" "C=$C $(j -c '{dailyProteinTargetG,dailyCarbsTargetG,dailyFatTargetG}')"
AS=A
pf() { j "[.dailyProteinTargetG,.dailyCarbsTargetG,.dailyFatTargetG]|map(tostring)|join(\",\")"; }
r PUT /me/profile '{"dailyProteinTargetG":120,"dailyCarbsTargetG":250,"dailyFatTargetG":70}' "$(uid)"; check "MT02 PUT sets all three (200, echoed)" "$([ "$C" = 200 ] && [ "$(pf)" = 120,250,70 ] && echo 0 || echo 1)" "C=$C $(pf)"
r GET /me/profile; check "MT03 GET returns them" "$([ "$(pf)" = 120,250,70 ] && echo 0 || echo 1)" "$(pf)"
r PUT /me/profile '{"dailyCarbsTargetG":null}' "$(uid)"; r GET /me/profile; check "MT04 explicit null clears carbs, others kept" "$([ "$(pf)" = 120,null,70 ] && echo 0 || echo 1)" "$(pf)"
r PUT /me/profile '{"dailyFatTargetG":0}' "$(uid)"; check "MT05 PUT 0 -> 200, returned as null" "$([ "$C" = 200 ] && [ "$(j .dailyFatTargetG)" = null ] && echo 0 || echo 1)" "C=$C fat=$(j .dailyFatTargetG)"
r GET /me/profile; check "MT06 GET after 0: null, protein kept" "$([ "$(pf)" = 120,null,null ] && echo 0 || echo 1)" "$(pf)"
for v in -1 1001 12.5 '"50"'; do r PUT /me/profile "{\"dailyProteinTargetG\":$v}" "$(uid)"; check "MT07 protein $v -> 400" "$(is 400)" "C=$C"; done
r PUT /me/profile '{"dailyCarbsTargetG":1000}' "$(uid)"; check "MT08 1000 accepted" "$([ "$C" = 200 ] && [ "$(j .dailyCarbsTargetG)" = 1000 ] && echo 0 || echo 1)" "C=$C"
r PUT /me/profile '{"dailyCarbsTargetG":null}' "$(uid)"
r GET /me/profile; check "MT09 invalid PUTs changed nothing (protein 120)" "$([ "$(pf)" = 120,null,null ] && echo 0 || echo 1)" "$(pf)"
r PUT /me/profile '{"dailyCalorieTargetKcal":2200}' "$(uid)"; check "MT10 setting kcal target doesn't derive macros" "$([ "$(pf)" = 120,null,null ] && echo 0 || echo 1)" "$(pf)"
r PUT /me/profile '{"dailyCalorieTargetKcal":null}' "$(uid)"
r GET "/nutrition/summary?from=$YDAY&to=$TODAY"
check "MT11 summary targets: protein 120, carbs/fat null, non-null with only a macro set" "$([ "$(j '.targets.proteinG')" = 120 ] && [ "$(j '.targets.carbsG')" = null ] && [ "$(j '.targets.fatG')" = null ] && echo 0 || echo 1)" "$(j -c .targets)"
PT=".points[]|select(.period==\"$TODAY\")"
check "MT12 today: proteinPct = protein/120*100 (1 dp), carbs/fat pct null" "$(python3 -c "
import json,sys,decimal as D
d=json.load(open(sys.argv[1])); p=[x for x in d['points'] if x['period']==sys.argv[2]][0]; a=p['adherence']
prot=p['totals']['proteinG']; exp=(D.Decimal(str(prot))/120*100).quantize(D.Decimal('0.1'),D.ROUND_HALF_UP)
ok=a and a['carbsPct'] is None and a['fatPct'] is None and a['proteinPct'] is not None and abs(D.Decimal(str(a['proteinPct']))-exp)<=D.Decimal('0.1')
sys.exit(0 if ok else 1)" "$B" "$TODAY" && echo 0 || echo 1)" "$(j -c "$PT|{totals:{proteinG:.totals.proteinG},adherence}")"
check "MT13 yesterday (no food): adherence null" "$([ "$(j ".points[]|select(.period==\"$YDAY\")|.adherence")" = null ] && echo 0 || echo 1)" "$(j -c ".points[]|select(.period==\"$YDAY\")|.adherence")"
r GET "/nutrition/summary?from=$WFROM&to=$TODAY&bucket=month"
check "MT14 current targets apply to the whole range (month bucket echoes protein 120)" "$([ "$C" = 200 ] && [ "$(j .targets.proteinG)" = 120 ] && echo 0 || echo 1)" "C=$C"
r GET /me/export; check "MT15 export includes macro targets" "$([ "$C" = 200 ] && [ "$(j '[.. | objects | select(has("dailyProteinTargetG"))][0].dailyProteinTargetG')" = 120 ] && echo 0 || echo 1)" "C=$C"
AS=B
r GET /me/profile; check "MT16 other user unaffected (null targets)" "$([ "$(pf)" = null,null,null ] && echo 0 || echo 1)" "$(pf)"
r GET "/nutrition/summary?from=$YDAY&to=$TODAY"; check "MT17 other user summary targets null" "$([ "$(j .targets)" = null ] && echo 0 || echo 1)" "$(j -c .targets)"
AS=""
r PUT /me/profile '{"dailyProteinTargetG":10}' "$(uid)"; check "MT18 unauthenticated PUT -> 401" "$(is 401)" "C=$C"

echo "== Progression suggestions (#90) =="
AS=A
declare -A X
for s in dumbbell-bench-press leg-press dumbbell-bicep-curl dumbbell-shoulder-press dumbbell-row lat-pulldown cable-tricep-pushdown push-up bodyweight-squat plank superman brisk-walk dumbbell-goblet-squat glute-bridge; do
  r GET "/exercises?query=$s&limit=20"; X[$s]="$(j "[.. | objects | select(.slug? == \"$s\") | .id][0] // empty")"
done
BP=${X[dumbbell-bench-press]}; LP=${X[leg-press]}; BC=${X[dumbbell-bicep-curl]}; SPR=${X[dumbbell-shoulder-press]}
ROW=${X[dumbbell-row]}; LAT=${X[lat-pulldown]}; TRI=${X[cable-tricep-pushdown]}; PU=${X[push-up]}; SQ=${X[bodyweight-squat]}
PL=${X[plank]}; SUP=${X[superman]}; WALK=${X[brisk-walk]}; GOB=${X[dumbbell-goblet-squat]}; GB=${X[glute-bridge]}
# pe ORDER EXID SETS REPSMIN REPSMAX DURATION WEIGHT (use null for none)
pe() { jq -nc --argjson o "$1" --arg x "$2" --argjson s "$3" --argjson a "$4" --argjson b "$5" --argjson d "$6" --argjson w "$7" \
  '{exerciseId:$x,order:$o,sets:$s,repsMin:$a,repsMax:$b,durationSeconds:$d,targetWeightKg:$w,restSeconds:60}'; }
W1="[$(pe 0 "$BP" 2 8 10 null 40),$(pe 1 "$LP" 2 8 10 null 100),$(pe 2 "$BC" 2 8 10 null 8),$(pe 3 "$SPR" 2 8 10 null 40.3),$(pe 4 "$ROW" 2 8 10 null null),$(pe 5 "$LAT" 2 8 10 null 50),$(pe 6 "$TRI" 2 8 10 null 30),$(pe 7 "$PU" 2 8 12 null null),$(pe 8 "$SQ" 2 10 15 null null),$(pe 9 "$PL" 2 null null 60 null),$(pe 10 "$SUP" 2 null null 65 null),$(pe 11 "$WALK" 2 null null 300 null)]"
W2="[$(pe 0 "$BP" 2 8 10 null 40),$(pe 1 "$PU" 2 19 20 null null)]"
PLAN="$(jq -nc --argjson a "$W1" --argjson b "$W2" '{name:"QA progression",weeks:4,generator:"custom",rationale:null,workouts:[{dayOfWeek:1,name:"W1",exercises:$a},{dayOfWeek:3,name:"W2",exercises:$b}]}')"
r POST /workout-plans "$PLAN" "$(uid)"; PG="$(j .id)"
r GET "/workout-plans/$PG"; PV="$(j .version)"; PW1="$(j '.workouts[0].id')"; PW2="$(j '.workouts[1].id')"
SG="$(mktemp -t p1.sugg.XXXXXXXX)"; TMPS+=("$SG")
sug() { r GET "/workout-plans/$1/progression-suggestions"; cp "$B" "$SG"; }
# s W EXID WORKOUT -> jq on the suggestion for that exercise in that workout
s() { jq -rc --arg w "$1" --arg x "$2" "[.suggestions[]|select(.plannedWorkoutId==\$w and .exerciseId==\$x)][0]|$3" "$SG" 2>/dev/null; }
sug "$PG"
check "PG01 empty history: 200, planVersion, 14 suggestions, window 3" "$([ "$C" = 200 ] && [ "$(j '.suggestions|length')" = 14 ] && [ "$(j .planVersion)" = "$PV" ] && [ "$(j .windowSessions)" = 3 ] && echo 0 || echo 1)" "C=$C n=$(j '.suggestions|length')"
check "PG02 all hold NOT_ENOUGH_HISTORY, order 0..11,0,1" "$([ "$(j '[.suggestions[]|select(.action=="hold" and .reason=="NOT_ENOUGH_HISTORY")]|length')" = 14 ] && [ "$(jq -c '[.suggestions[].order]' "$B")" = '[0,1,2,3,4,5,6,7,8,9,10,11,0,1]' ] && echo 0 || echo 1)" "$(jq -c '[.suggestions[]|[.order,.reason]]' "$B")"
# session PW DIFFICULTY then set specs "EXID reps weight dur dist" per set
SN=0
session() {
  local pw=$1 pd=$2; shift 2; local i=0 sid
  r POST /workout-sessions "$(jq -nc --arg p "$pw" '{plannedWorkoutId:$p}')" "$(uid)"; sid="$(j .id)"
  for spec in "$@"; do
    set -- $spec
    r POST "/workout-sessions/$sid/sets" "$(jq -nc --arg c "$(uid)" --arg x "$1" --argjson i $i --argjson r "$2" --argjson w "$3" --argjson d "$4" --argjson m "$5" \
      '{clientSetId:$c,exerciseId:$x,setIndex:$i,reps:$r,weightKg:$w,durationSeconds:$d,distanceM:$m,completed:true}')" "$(uid)"
    [ "$C" = 201 ] || [ "$C" = 200 ] || echo "  set failed: $C $(j -c .)"
    i=$((i+1))
  done
  r POST "/workout-sessions/$sid/complete" "{\"perceivedDifficulty\":$pd}" "$(uid)"; SN=$((SN+1)); [ "$C" = 200 ] || echo "  complete failed: $C"
}
two() { echo "$1" "$1"; }
w1() { # w1 DIFFICULTY LATREPS TRIKG SQREPS SUPSEC
  session "$PW1" "$1" "$BP 10 40 null null" "$BP 10 40 null null" "$LP 10 100 null null" "$LP 10 100 null null" \
    "$BC 10 8 null null" "$BC 10 8 null null" "$SPR 10 40.3 null null" "$SPR 10 40.3 null null" \
    "$ROW 10 22.3 null null" "$ROW 10 20 null null" "$LAT $2 50 null null" "$LAT 10 50 null null" \
    "$TRI 10 $3 null null" "$TRI 10 $3 null null" "$PU 20 null null null" "$PU 20 null null null" \
    "$SQ $4 null null null" "$SQ $4 null null null" "$PL null null 60 null" "$PL null null 60 null" \
    "$SUP null null $5 null" "$SUP null null $5 null" "$WALK null null 300 2000" "$WALK null null 300 2000"
}
w1 3 9 30 15 65
w1 2 10 27.5 12 50
sug "$PG"; check "PG03 two sessions: still NOT_ENOUGH_HISTORY (BP, LP)" "$([ "$(s "$PW1" "$BP" .reason)" = NOT_ENOUGH_HISTORY ] && [ "$(s "$PW1" "$LP" .reason)" = NOT_ENOUGH_HISTORY ] && echo 0 || echo 1)" "BP=$(s "$PW1" "$BP" .reason)"
w1 3 10 27.5 12 50
sug "$PG"
sw() { s "$PW1" "$1" "$2"; }
inc() { [ "$(sw "$1" .action)/$(sw "$1" .reason)/$(sw "$1" "$2")" = "increase/ALL_SETS_COMPLETED/$3" ] && echo 0 || echo 1; }
check "PG04 BP (chest 40) -> increase 41" "$(inc "$BP" .suggested.targetWeightKg 41)" "$(sw "$BP" .)"
check "PG05 LP (quads 100, 5%) -> increase 105" "$(inc "$LP" .suggested.targetWeightKg 105)" "$(sw "$LP" .)"
check "PG06 BC (8 kg, min step 0.5) -> 8.5" "$(inc "$BC" .suggested.targetWeightKg 8.5)" "$(sw "$BC" .)"
check "PG07 SPR (40.3 off-grid) -> 41.5" "$(inc "$SPR" .suggested.targetWeightKg 41.5)" "$(sw "$SPR" .)"
check "PG08 ROW no target -> increase NO_TARGET_SET 22 (heaviest 22.3 floored)" "$([ "$(sw "$ROW" '.action+"/"+.reason+"/"+(.suggested.targetWeightKg|tostring)')" = increase/NO_TARGET_SET/22 ] && echo 0 || echo 1)" "$(sw "$ROW" .)"
check "PG09 PU W1 (8-12 reps) -> 9-13" "$(inc "$PU" '(.suggested.repsMin|tostring)+"-"+(.suggested.repsMax|tostring)' 9-13)" "$(sw "$PU" .)"
check "PG10 PU W2 (19-20) -> hold REP_CEILING with harderVariations array" "$([ "$(s "$PW2" "$PU" '.action+"/"+.reason+"/"+(.harderVariations|type)')" = hold/REP_CEILING/array ] && echo 0 || echo 1)" "$(s "$PW2" "$PU" .)"
check "PG11 PL 60 s -> 65" "$(inc "$PL" .suggested.durationSeconds 65)" "$(sw "$PL" .)"
check "PG12 WALK 300 s -> 330" "$(inc "$WALK" .suggested.durationSeconds 330)" "$(sw "$WALK" .)"
check "PG13 LAT missed once -> hold MISSED_TARGET" "$([ "$(sw "$LAT" '.action+"/"+.reason')" = hold/MISSED_TARGET ] && echo 0 || echo 1)" "$(sw "$LAT" .)"
check "PG14 TRI missed twice -> deload REPEATED_MISSES 27" "$([ "$(sw "$TRI" '.action+"/"+.reason+"/"+(.suggested.targetWeightKg|tostring)')" = deload/REPEATED_MISSES/27 ] && echo 0 || echo 1)" "$(sw "$TRI" .)"
check "PG15 SUP missed twice -> deload 55 s" "$([ "$(sw "$SUP" '.action+"/"+.reason+"/"+(.suggested.durationSeconds|tostring)')" = deload/REPEATED_MISSES/55 ] && echo 0 || echo 1)" "$(sw "$SUP" .)"
check "PG16 SQ (reps) missed twice -> hold REPEATED_MISSES, no suggestion" "$([ "$(sw "$SQ" '.action+"/"+.reason+"/"+(.suggested|tostring)')" = hold/REPEATED_MISSES/null ] && echo 0 || echo 1)" "$(sw "$SQ" .)"
check "PG17 basedOn: 3 sessions newest first, BP met, best 40/10, WALK distance 2000" "$([ "$(sw "$BP" '.basedOn|length')" = 3 ] && [ "$(sw "$BP" '[.basedOn[].completedAt]|(.==(sort|reverse))')" = true ] && [ "$(sw "$BP" '[.basedOn[]|.metTarget and .bestWeightKg==40 and .bestReps==10 and .completedSets==2 and .plannedSets==2]|all')" = true ] && [ "$(sw "$WALK" '.basedOn[0].bestDistanceM')" = 2000 ] && [ "$(sw "$BP" '[.basedOn[].perceivedDifficulty]|tostring')" = '[3,2,3]' ] && echo 0 || echo 1)" "$(sw "$BP" -c .basedOn)"
check "PG18 all suggested weights on the 0.5 kg grid, no cooldownUntil yet" "$(jq -e '[.suggestions[]|.suggested.targetWeightKg//empty|(.*2)|(.==floor)]|all' "$SG" >/dev/null && [ "$(jq '[.suggestions[]|select(.cooldownUntil!=null)]|length' "$SG")" = 0 ] && echo 0 || echo 1)" "$(jq -c '[.suggestions[].suggested.targetWeightKg]' "$SG")"
r GET "/workout-plans/$PG"; V1="$(j .version)"; r GET /xp/balance; XP1="$(j .total)"
sug "$PG"; r GET "/workout-plans/$PG"; V2="$(j .version)"; r GET /xp/balance; XP2="$(j .total)"
check "PG19 GET is read-only: plan version and XP unchanged" "$([ "$V1" = "$V2" ] && [ "$XP1" = "$XP2" ] && echo 0 || echo 1)" "version $V1->$V2 xp $XP1->$XP2"
r GET "/workout-plans/$PG"
ACC="$(jq -c --argjson v "$V2" '{name,weeks,generator:"custom",rationale,baseVersion:$v,workouts:[.workouts[]|{dayOfWeek,name,exercises:[.exercises[]|{exerciseId,order,sets,repsMin,repsMax,durationSeconds,targetWeightKg,restSeconds:(.restSeconds//60)}]}]}
  | .workouts[0].exercises[0].targetWeightKg=41 | .workouts[0].exercises[4].targetWeightKg=22 | .workouts[0].exercises[7].repsMin=9 | .workouts[0].exercises[7].repsMax=13' "$B")"
r PATCH "/workout-plans/$PG" "$ACC" "$(uid)"; TP="$(date -u +%s)"; PV2="$(j .version)"
check "PG20 accept BP/ROW/PU (W1) with PATCH baseVersion -> version+1" "$([ "$C" = 200 ] && [ "$PV2" = $((V2+1)) ] && echo 0 || echo 1)" "C=$C v=$PV2 $(j .message)"
r GET "/workout-plans/$PG"; PW1="$(j '.workouts[0].id')"; PW2="$(j '.workouts[1].id')"   # a new version has new planned-workout ids
sug "$PG"
CU="$(sw "$BP" .cooldownUntil)"; CUS="$(date -u -d "$CU" +%s 2>/dev/null || echo 0)"
check "PG21 BP W1 -> hold COOLDOWN, cooldownUntil ~ PATCH + 7 d" "$([ "$(sw "$BP" '.action+"/"+.reason')" = hold/COOLDOWN ] && [ $(( CUS - TP - 604800 )) -le 120 ] && [ $(( CUS - TP - 604800 )) -ge -120 ] && echo 0 || echo 1)" "$(sw "$BP" '{action,reason,cooldownUntil}') patch=$TP"
check "PG22 PU W1 (9-13 accepted) -> COOLDOWN" "$([ "$(sw "$PU" .reason)" = COOLDOWN ] && echo 0 || echo 1)" "$(sw "$PU" '{action,reason}')"
check "PG23 BP W2 (not raised) still increase 41, same basedOn" "$([ "$(s "$PW2" "$BP" '.action+"/"+(.suggested.targetWeightKg|tostring)+"/"+(.basedOn|length|tostring)+"/"+.plannedWorkoutId')" = "increase/41/3/$PW2" ] && echo 0 || echo 1)" "$(s "$PW2" "$BP" '{action,reason,suggested}')"
check "PG24 ROW 22 (target set from null: not a raise) -> increase 23, no cooldown" "$([ "$(sw "$ROW" '.action+"/"+.reason+"/"+(.suggested.targetWeightKg|tostring)+"/"+(.cooldownUntil|tostring)')" = increase/ALL_SETS_COMPLETED/23/null ] && echo 0 || echo 1)" "$(sw "$ROW" '{action,reason,suggested,cooldownUntil}')"
check "PG25 LP untouched still increase 105, planVersion = new version" "$([ "$(sw "$LP" .suggested.targetWeightKg)" = 105 ] && [ "$(jq -r .planVersion "$SG")" = $((V2+1)) ] && echo 0 || echo 1)" "$(sw "$LP" -c .suggested) pv=$(jq -r .planVersion "$SG")"
r PATCH "/workout-plans/$PG" "$ACC" "$(uid)"; check "PG26 PATCH stale baseVersion -> 409 VERSION_CONFLICT" "$(code_is 409 VERSION_CONFLICT)" "C=$C code=$(j .code)"

EFF="$(jq -nc --argjson a "[$(pe 0 "$GOB" 2 8 10 null 20),$(pe 1 "$GB" 2 10 12 null null)]" '{name:"QA effort",weeks:4,generator:"custom",rationale:null,workouts:[{dayOfWeek:2,name:"E1",exercises:$a}]}')"
r POST /workout-plans "$EFF" "$(uid)"; PE="$(j .id)"; r GET "/workout-plans/$PE"; PWE="$(j '.workouts[0].id')"
e1() { session "$PWE" "$1" "$GOB 10 20 null null" "$GOB 10 20 null null" "$GB 12 null null null" "$GB 12 null null null"; }
e1 5; e1 5; sug "$PE"
check "PG27 two very hard sessions: GOB -> deload VERY_HARD 18" "$([ "$(s "$PWE" "$GOB" '.action+"/"+.reason+"/"+(.suggested.targetWeightKg|tostring)')" = deload/VERY_HARD/18 ] && echo 0 || echo 1)" "$(s "$PWE" "$GOB" '{action,reason,suggested}')"
check "PG28 GB (reps) -> hold VERY_HARD, no suggestion" "$([ "$(s "$PWE" "$GB" '.action+"/"+.reason+"/"+(.suggested|tostring)')" = hold/VERY_HARD/null ] && echo 0 || echo 1)" "$(s "$PWE" "$GB" '{action,reason}')"
e1 3; sug "$PE"
check "PG29 third session at 3 (window has a 5): GOB and GB hold HIGH_EFFORT" "$([ "$(s "$PWE" "$GOB" .reason)/$(s "$PWE" "$GB" .reason)" = HIGH_EFFORT/HIGH_EFFORT ] && echo 0 || echo 1)" "$(s "$PWE" "$GOB" .reason)/$(s "$PWE" "$GB" .reason)"
r GET "/workout-plans/$(uid)/progression-suggestions"; check "PG30 unknown plan id -> 404" "$(is 404)" "C=$C"
r GET "/workout-plans/not-a-uuid/progression-suggestions"; check "PG31 malformed plan id -> 404" "$(is 404)" "C=$C"
AS=B
r GET "/workout-plans/$PG/progression-suggestions"; check "PG32 other user -> 404" "$(is 404)" "C=$C"
r POST /workout-sessions "$(jq -nc --arg p "$PW1" '{plannedWorkoutId:$p}')" "$(uid)"; check "PG33 other user starts session on A's planned workout -> 404" "$(is 404)" "C=$C"
r POST /workout-plans "$(jq -nc --argjson a "[$(pe 0 "$BP" 2 8 10 null 40)]" '{name:"QA B",weeks:4,generator:"custom",rationale:null,workouts:[{dayOfWeek:1,name:"B1",exercises:$a}]}')" "$(uid)"; PB="$(j .id)"
sug "$PB"; check "PG34 B's own plan (same exercise): no history from A" "$([ "$C" = 200 ] && [ "$(jq -r '.suggestions[0].reason+"/"+(.suggestions[0].basedOn|length|tostring)' "$SG")" = NOT_ENOUGH_HISTORY/0 ] && echo 0 || echo 1)" "$(jq -c '.suggestions[0]|{reason,basedOn}' "$SG")"
AS=A
r DELETE "/workout-plans/$PE" '' "$(uid)"; r GET "/workout-plans/$PE/progression-suggestions"
check "PG35 archived plan -> 409 PLAN_ARCHIVED" "$(code_is 409 PLAN_ARCHIVED)" "C=$C code=$(j .code)"
AS=B; r GET "/workout-plans/$PE/progression-suggestions"; check "PG36 other user on archived plan -> 404 (not 409)" "$(is 404)" "C=$C"
AS=""; r GET "/workout-plans/$PG/progression-suggestions"; check "PG37 unauthenticated -> 401" "$(is 401)" "C=$C"
AS=A

# Delete both accounts now (trap also covers failures).
for a in A B; do AS=$a; r DELETE /me "$(cred $a '{password:$p}')" "$(uid)"; [ "$C" = 204 ] && DELETED[$a]=1; done
AS=""
[ "$FAIL" = 0 ]
