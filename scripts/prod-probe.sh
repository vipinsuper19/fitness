#!/usr/bin/env bash
# Manual prod probe of the badges & challenges flow with ONE throwaway account:
# register -> join WEEKLY_WORKOUTS (3) -> start session -> log 1 completed set ->
# complete -> GET challenge (expect 1/3) -> GET /badges (expect FIRST_WORKOUT) ->
# leave -> DELETE /me -> login must be 401.
#   scripts/prod-probe.sh               # against prod (or API=... for another origin)
#   scripts/prod-probe.sh --self-test   # same script against a local stub; run first
# Safety: credentials live only in a mode-600 state file (/tmp/prod-probe.state) so the
# EXIT trap can always log in and DELETE /me, even if a step fails; the file is
# removed once login returns 401. All JSON bodies are built with jq (no hand quoting).
# Never prints tokens or the password.
set -euo pipefail
if [ "${1:-}" = --self-test ]; then
  PORT=8799; STUB="$(mktemp)"; cat > "$STUB" <<'PY'
import http.server,json
class H(http.server.BaseHTTPRequestHandler):
    def log_message(s,*a): pass
    def h(s):
        n=int(s.headers.get('Content-Length') or 0); b=s.rfile.read(n).decode()
        if b: json.loads(b)
        p=s.path; m=s.command
        code,o=200,{}
        if p.endswith('/auth/register'): code,o=201,{"tokens":{"accessToken":"x"}}
        elif p.endswith('/auth/login'): code=401
        elif m=='DELETE': code=204
        elif p.endswith('/challenges'): code,o=201,{"id":"c1","status":"active","progress":0,"target":3,"joinDate":"d"}
        elif 'exercises' in p: o={"items":[{"id":"e1","name":"Squat"}]}
        elif p.endswith('/workout-sessions'): code,o=201,{"id":"s1","status":"in_progress"}
        assert m!='POST' or 'Idempotency-Key' in s.headers or 'auth' in p or 'leave' in p, p
        s.send_response(code); s.end_headers()
        if code!=204: s.wfile.write(json.dumps(o).encode())
    do_GET=do_POST=do_DELETE=h
http.server.HTTPServer(('127.0.0.1',int(__import__('sys').argv[1])),H).serve_forever()
PY
  python3 "$STUB" "$PORT" & SP=$!; sleep 1
  API="http://127.0.0.1:$PORT/api/v1" bash "$0"; rc=$?; kill "$SP"; rm -f "$STUB"; exit $rc
fi
API="${API:-https://fitness-api-6pcc.onrender.com/api/v1}"
ST=/tmp/prod-probe.state; B=/tmp/probe2.body
E="probe-$(openssl rand -hex 4)@example.invalid"; P="Pr0be-$(openssl rand -hex 12)"
(umask 077; printf '%s\n%s\n' "$E" "$P" > "$ST")
T=""; DEL=0; CREATED=0
uid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
now() { TZ=Asia/Kolkata date +%H:%M:%S; }
cred() { jq -nc --arg e "$(sed -n 1p "$ST")" --arg p "$(sed -n 2p "$ST")" "$1"; }
# r METHOD PATH [BODY] [IDEMPOTENCY_KEY]; sets C, body in $B
r() {
  local a=(-sS -o "$B" -w '%{http_code}' --max-time 90 -X "$1" "$API$2" -H 'Content-Type: application/json')
  [ -n "$T" ] && a+=(-H "Authorization: Bearer $T")
  [ -n "${4:-}" ] && a+=(-H "Idempotency-Key: $4")
  [ -n "${3:-}" ] && a+=(--data-binary "$3")
  C="$(curl "${a[@]}" || echo 000)"
  echo "$(now) IST | $1 $2 | $C"
}
show() { jq -c "$1" "$B" 2>/dev/null || echo "  (unparseable body)"; }
token() { T="$(jq -r '[.. | objects | .accessToken? // empty][0] // empty' "$B")"; }
cleanup() {
  set +e
  if [ "$CREATED" = 1 ] && [ "$DEL" = 0 ]; then
    if [ -z "$T" ]; then r POST /auth/login "$(cred '{email:$e,password:$p}')"; token; fi
    r DELETE /me "$(cred '{password:$p}')"; [ "$C" = 204 ] && DEL=1
  fi
  T=""; r POST /auth/login "$(cred '{email:$e,password:$p}')"; echo "  ^ login after delete (expect 401)"
  [ "$C" = 401 ] && rm -f "$ST"; rm -f "$B"
}
trap cleanup EXIT
echo "account $E"
r POST /auth/register "$(cred '{email:$e,password:$p,displayName:"Probe"}')"; CREATED=1; token
[ -n "$T" ] || { echo "no token"; exit 1; }
r POST /challenges '{"templateCode":"WEEKLY_WORKOUTS","target":3}' "$(uid)"; show '{id,status,progress,target,joinDate}'
CID="$(jq -r .id "$B")"
r GET '/exercises?limit=1'; EX="$(jq -r '[.. | objects | select(has("id") and has("name")) | .id][0] // empty' "$B")"; echo "  exerciseId $EX"
r POST /workout-sessions '{}' "$(uid)"; show '{id,status}'; SID="$(jq -r .id "$B")"
r POST "/workout-sessions/$SID/sets" "$(jq -nc --arg c "$(uid)" --arg x "$EX" '{clientSetId:$c,exerciseId:$x,setIndex:0,reps:10,completed:true}')" "$(uid)"; show '{completed,reps,setIndex}'
r POST "/workout-sessions/$SID/complete" '{"perceivedDifficulty":3}' "$(uid)"; show '{status:.session.status,xpAwarded,xpBalance}'
sleep 2
r GET "/challenges/$CID"; show '{status,progress,target}'
r GET /badges; show '{earnedCount,earned:[.items[]|select(.earnedAt)|{code,earnedAt}]}'
r POST "/challenges/$CID/leave" '' ; show '{status,leftAt,progress}'
r DELETE /me "$(cred '{password:$p}')"; [ "$C" = 204 ] && DEL=1
