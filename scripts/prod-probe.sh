#!/usr/bin/env bash
# Manual prod probe of the badges & challenges flow with ONE throwaway account:
# register -> join WEEKLY_WORKOUTS (3) -> start session -> log 1 completed set ->
# complete -> GET challenge (expect 1/3) -> GET /badges (expect FIRST_WORKOUT) ->
# leave -> DELETE /me -> login must be 401.
#   scripts/prod-probe.sh               # against prod (or API=... for another origin)
#   scripts/prod-probe.sh --self-test   # same script against a local stub; run first
# Safety: credentials live only in a per-run mode-600 state file (mktemp under umask 077)
# so the EXIT trap can always log in and DELETE /me, even if a step fails; the file is
# removed once login returns 401, and its path is printed if cleanup could not finish.
# The password and token never appear in any process's argv: jq reads them from the
# state file (--rawfile), and curl reads request bodies and the Authorization header
# from mode-600 temp files (--data-binary @file, -H @file). All JSON is built with jq.
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
umask 077
ST="$(mktemp -t prod-probe.state.XXXXXXXX)"; B="$(mktemp -t prod-probe.body.XXXXXXXX)"
BF="$(mktemp -t prod-probe.req.XXXXXXXX)"; HF="$(mktemp -t prod-probe.hdr.XXXXXXXX)"
E="probe-$(openssl rand -hex 4)@example.invalid"
# The password is generated straight into the state file; it is never a shell argument.
{ printf '%s\n' "$E"; printf 'Pr0be-'; openssl rand -hex 12; } > "$ST"
T=""; DEL=0; CREATED=0
uid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
now() { TZ=Asia/Kolkata date +%H:%M:%S; }
# cred FILTER: JSON built from the state file ($e email, $p password) without argv exposure.
cred() { jq -nc --rawfile s "$ST" '($s | split("\n")) as $l | $l[0] as $e | $l[1] as $p | '"$1"; }
# r METHOD PATH [BODY] [IDEMPOTENCY_KEY]; sets C, body in $B
r() {
  local a=(-sS -o "$B" -w '%{http_code}' --max-time 90 -X "$1" "$API$2" -H 'Content-Type: application/json')
  if [ -n "$T" ]; then printf 'Authorization: Bearer %s\n' "$T" > "$HF"; a+=(-H "@$HF"); fi
  [ -n "${4:-}" ] && a+=(-H "Idempotency-Key: $4")
  if [ -n "${3:-}" ]; then printf '%s' "$3" > "$BF"; a+=(--data-binary "@$BF"); fi
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
  if [ "$C" = 401 ]; then rm -f "$ST"; else echo "  cleanup unfinished; credentials kept in $ST (mode 600)"; fi
  rm -f "$B" "$BF" "$HF"
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
