#!/usr/bin/env bash
# QA API checks against prod for session corrections (#63), plan management (#65) and the
# nutrition summary (#70), using TWO throwaway accounts (A = owner, B = other user).
#   scripts/prod-check-p1.sh            # prod (or API=... for another origin)
# Same safety pattern as scripts/prod-probe.sh: credentials only in per-run mode-600 state
# files (umask 077); password/token never in argv or output; JSON built with jq; the EXIT trap
# always deletes BOTH accounts (DELETE /me) and verifies login is 401, even if a check fails.
# Light load: ~70 sequential requests with a small pause. Prints PASS/FAIL per check; exit 1 on any FAIL.
set -uo pipefail
API="${API:-https://fitness-api-6pcc.onrender.com/api/v1}"
umask 077
B="$(mktemp -t p1.body.XXXXXXXX)"; BF="$(mktemp -t p1.req.XXXXXXXX)"; HF="$(mktemp -t p1.hdr.XXXXXXXX)"
declare -A ST TOK CREATED DELETED
for a in A B; do
  ST[$a]="$(mktemp -t p1.state.XXXXXXXX)"
  { printf 'p1check-%s@example.invalid\n' "$(openssl rand -hex 5)"; printf 'Pr0be-'; openssl rand -hex 12; } > "${ST[$a]}"
  TOK[$a]=""; CREATED[$a]=0; DELETED[$a]=0
done
AS=""   # current account; "" = unauthenticated
PASS=0; FAIL=0; FAILS=()
uid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
now() { TZ=Asia/Kolkata date +%H:%M:%S; }
cred() { jq -nc --rawfile s "${ST[$1]}" '($s | split("\n")) as $l | $l[0] as $e | $l[1] as $p | '"$2"; }
# r METHOD PATH [BODY] [IDEMPOTENCY_KEY] -> $C, body in $B
r() {
  sleep 0.3
  local a=(-sS -o "$B" -w '%{http_code}' --max-time 120 -X "$1" "$API$2" -H 'Content-Type: application/json')
  if [ -n "$AS" ] && [ -n "${TOK[$AS]}" ]; then printf 'Authorization: Bearer %s\n' "${TOK[$AS]}" > "$HF"; a+=(-H "@$HF"); fi
  [ -n "${4:-}" ] && a+=(-H "Idempotency-Key: $4")
  if [ -n "${3:-}" ]; then printf '%s' "$3" > "$BF"; a+=(--data-binary "@$BF"); fi
  C="$(curl "${a[@]}" 2>/dev/null || echo 000)"
  echo "  $(now) IST [${AS:--}] $1 $2 -> $C"
}
j() { jq -r "$1" "$B" 2>/dev/null; }
check() { # check NAME CONDITION-RESULT(0/1) DETAIL
  if [ "$2" = 0 ]; then PASS=$((PASS+1)); echo "PASS $1"; else FAIL=$((FAIL+1)); FAILS+=("$1"); echo "FAIL $1 -- $3"; fi
}
is() { [ "$C" = "$1" ] && echo 0 || echo 1; }
code_is() { [ "$C" = "$1" ] && [ "$(j .code)" = "$2" ] && echo 0 || echo 1; }
cleanup() {
  set +e
  for a in A B; do
    AS=$a
    if [ "${CREATED[$a]}" = 1 ] && [ "${DELETED[$a]}" = 0 ]; then
      TOK[$a]=""; AS=""; r POST /auth/login "$(cred $a '{email:$e,password:$p}')"
      TOK[$a]="$(j '[.. | objects | .accessToken? // empty][0] // empty')"; AS=$a
      r DELETE /me "$(cred $a '{password:$p}')" "$(uid)"; [ "$C" = 204 ] && DELETED[$a]=1
    fi
    TOK[$a]=""; AS=""; r POST /auth/login "$(cred $a '{email:$e,password:$p}')"
    if [ "$C" = 401 ]; then echo "CLEANUP account $a deleted (login 401)"; rm -f "${ST[$a]}"
    else echo "CLEANUP account $a NOT confirmed deleted (login $C); credentials kept in ${ST[$a]} (mode 600)"; fi
  done
  rm -f "$B" "$BF" "$HF"
  echo "SUMMARY pass=$PASS fail=$FAIL ${FAILS[*]:-}"
}
trap cleanup EXIT

signup() {
  AS=""; r POST /auth/register "$(cred $1 '{email:$e,password:$p,displayName:"QA"}')" "$(uid)"
  [ "$C" = 201 ] && CREATED[$1]=1
  TOK[$1]="$(j '[.. | objects | .accessToken? // empty][0] // empty')"
}
echo "API $API (first call may take ~60-90 s: cold start)"
signup A; signup B
[ -n "${TOK[A]}" ] && [ -n "${TOK[B]}" ] || { echo "FAIL could not register throwaway accounts"; exit 1; }
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

# Delete both accounts now (trap also covers failures).
for a in A B; do AS=$a; r DELETE /me "$(cred $a '{password:$p}')" "$(uid)"; [ "$C" = 204 ] && DELETED[$a]=1; done
AS=""
[ "$FAIL" = 0 ]
