# Fitness MVP — QA test plan (T16a)

Scope: Lean MVP (P0) per `fitness-plan.md` and `contracts/openapi.yaml` v1 (`/api/v1`). P1 (AI photo food, challenges, leaderboards, rewards, Health Connect, FCM) is out of scope.

## Levels
| Level | Tooling | Where | Gate |
|---|---|---|---|
| Unit (backend) | Jest | `backend/test/*.spec.ts` | CI `backend.yml` |
| Contract | ajv + redocly lint on `contracts/openapi.yaml` | `contract.spec.ts`, `npm run contract:lint` | CI |
| API integration / e2e | Jest + supertest + Postgres 16 service (`DB_TESTS=1`) | `*.int-spec.ts`, `*.e2e-spec.ts` (incl. `qa-access-idempotency.e2e-spec.ts`) | CI |
| Android unit | JUnit, Turbine, Robolectric | `android/**/test` | CI `android.yml` |
| Android UI | Compose UI tests / instrumented | `android/**/androidTest` | per milestone (T16c) |
| Manual exploratory | Staging on Render Free (cold start ~50–90s) | — | before sign-off |

## Risk-based focus (must never regress)
1. **Access rules** — every user-owned route needs a valid access token (401 otherwise, incl. refresh token used as access token); other users' resources return 404 (no existence leak); export contains only the caller's data.
2. **Safe retries** — `Idempotency-Key` on writes: same key + same body replays (`Idempotent-Replayed: true`), different body → `409 IDEMPOTENCY_KEY_REUSED`, concurrent → one row, keys scoped per user, token responses never stored.
3. **XP integrity** — XP only from `POST /workout-sessions/{id}/complete`; one ledger row per (user, sourceType, sourceId) even under concurrent completes; never client-writable; reversal on discard.
4. **Data correctness** — canonical metric units; daily nutrition totals incl. overrides; progress `null` = missing, not 0.
5. **Account lifecycle** — refresh rotation + reuse detection revokes family; account deletion erases all data and kills tokens.
6. **Privacy/secrets** — no secrets in repo/logs; errors are `{code,message,details?,requestId}` without stack traces.

## Test environments & data
- CI: Postgres service, throwaway JWT secrets defined in workflow (not real secrets). Seeded exercise + Indian food catalog.
- Each test registers fresh users (`qa-<uuid>@example.in`) — no shared state, safe to run in any order.
- Local: `DB_TESTS=1 DATABASE_URL=... npm test` in `backend/`.

## Android UI tests (T16c) — screens needed before they can be written
- Auth: register / login / logout, token-expiry re-login.
- Onboarding (resumable, skippable optional fields, kg/lb, cm/ft-in) + profile edit.
- Workout tab: My Plan weekly calendar, Start New Plan (generate → preview → activate, custom plan), exercise library + detail.
- Workout tracking: start/pause/resume/finish, set checkbox, rest timer, skip/replace, difficulty feedback, offline draft + WorkManager sync.
- Food diary: meals, search, custom food, servings, macro override, water.
- Progress + Home dashboard: measurements, charts (missing ≠ zero), empty states, streak/XP.
- Settings: export data, delete account.

## Exit criteria for MVP sign-off
All CI checks green; every row of `acceptance-checklist.md` Pass or explicitly waived by Vipin; no open P0/P1 defects; manual smoke on staging and on one physical device (minSdk 26 + latest).

## §10 Badges & challenges — anti-abuse test rows

Source: `docs/specs/badges-challenges.md` §5 (weekly cap) and §9 (anti-abuse). (`fitness-plan.md` §10 is not in the repo, so these rows live here.)

| # | Rule | Steps | Expected | Automated by |
|---|---|---|---|---|
| AB1 | Future-dated entries don't count | Log food / water / measurement with `date` > today (profile tz); check badges + active challenge progress; advance the clock until that date arrives and re-check | Entry shows in diary; no badge, no challenge progress — not even once its day arrives | `backend/test/achievements.int-spec.ts` › "future-dated entries never count, even once their days arrive" |
| AB2 | Backdated entries count only within the window | Log an entry dated D at `createdAt` on D or D+1 (counts) and at ≥ start of D+2 (doesn't) | Rows count only if `date ≤ today` and `createdAt < startOfLocalDay(date + 2)` | `achievements.int-spec.ts` › "applies the backdating rule to client-dated entries" |
| AB3 | Re-dated entries don't count | Create an old (late) entry, then `PATCH` its `date` to today / yesterday | Still doesn't count (window is checked against original `createdAt`); badges already awarded stay | `achievements.int-spec.ts` › "re-dating an old entry through PATCH does not make it count; awarded badges stay" |
| AB4 | Challenge bonus XP capped at 150 / ISO week | Complete challenges in one Monday-start week (profile tz, week of `completed_at`) whose bonuses sum > 150 | Award trimmed to the remainder, then 0; `xp_awarded` stored on the challenge; ledger row only when > 0; capped amount never paid later; badge XP not capped | `achievements.int-spec.ts` › "caps challenge XP at 150 per Monday-start week: partial, then 0 with no ledger row" |
| AB5 | Cap week boundary | Complete one challenge Sunday 23:59 and one Monday 00:01 (profile tz) | Each counts toward its own week's 150 | manual / to automate |
| AB6 | Android shows capped XP | History card for a capped challenge | Shows `xpAwarded` (after cap), not the template's `bonusXp` | manual (UI) |

## Account deletion — retry behaviour (differs from contract)

`contracts/openapi.yaml` documents `DELETE /me` → `204` and says writes with a reused `Idempotency-Key` replay the stored response. **Observed on prod (2026-10-21, PR #59):** a same-key retry after a successful delete returns **`401 UNAUTHENTICATED`**, not a replayed `204`, because every token was revoked by the delete (the auth guard rejects the request before idempotency replay).

| # | Case | Expected app behaviour | Test |
|---|---|---|---|
| DA1 | First attempt → 204 | Local wipe (`SessionRepository.signOut()`), reset to Sign in, Back can't return | `delete_success_signsOut_toSignIn_andBackCannotReturn` |
| DA2 | First attempt outcome unknown (offline / 5xx / timeout), retry with **same key** → 401 | Treated as already deleted: local sign-out to Sign in | `delete_lostSuccess_retryGets401_treatedAsDeleted_signsOutToSignIn` |
| DA3 | 401 on a **first** attempt | Normal expired session (no "deleted" assumption) | ViewModel unit tests in #59 |
| DA4 | 403 `INVALID_PASSWORD` | Error, stays signed in, Confirm enabled, next attempt new key | `delete_wrongPassword_showsError_staysSignedIn_andCanRetry` |

Follow-up for Backend/contract: document the 401-after-delete retry in `openapi.yaml` (or replay 204 for that key) so clients don't rely on undocumented behaviour.

## P1 API checks — prod (`scripts/prod-check-p1.sh`)

Run: `scripts/prod-check-p1.sh` (or `API=… scripts/prod-check-p1.sh`). Only runs against `https://fitness-api-6pcc.onrender.com/api/v1` (host allowlist; the only Fitness API server, used as prod). `--self-test` checks the register-timeout recovery, INT/TERM cleanup and the allowlist against a local stub. Uses two throwaway accounts (A owner, B other user) with the `prod-probe.sh` safety pattern: credentials only in mode-600 state files under `~/.cache/p1-state` (outside /tmp, so a reboot mid-run cannot lose them; override with `P1_STATE_DIR`), never printed or in argv; the EXIT trap deletes both accounts and confirms login → 401.

**Stale state (killed runs):** every run starts by sweeping `$P1_STATE_DIR` (default `~/.cache/p1-state`); `scripts/prod-check-p1.sh --cleanup-stale` does only the sweep and exits (non-zero if any file is kept).

| # | Check | Self-test 2026-10-29 |
|---|---|---|
| CL-STALE | leftover state file whose account exists: login → DELETE /me → login 401 → file removed; account already gone (login 401): file removed; any other login result keeps the file. Self-test (stub): `--cleanup-stale` with one live + one gone account leaves 0 accounts / 0 files; a normal run sweeps a seeded stale account before signup. Self-test runs use their own temp state dir. | PASS |

**Last prod run: 2026-10-29 12:39:12 IST — 144/144 PASS (SC 20, PM 27, NS 12, ST 27, MT 21, PG 37), both accounts deleted (login 401).**

### Session corrections (#63)

`PUT/DELETE /workout-sessions/{id}/corrections/sets/{clientSetId}`, `PATCH /workout-sessions/{id}/completion` — only on completed sessions, Idempotency-Key required, XP re-derived.

| # | Check | Prod 2026-10-29 |
|---|---|---|
| SC01 | correct before complete -> 409 SESSION_NOT_COMPLETED | PASS |
| SC02 | complete 200 | PASS |
| SC03 | correction without Idempotency-Key -> 400 | PASS |
| SC04 | body clientSetId != path -> 400 | PASS |
| SC05 | correct set (reps 10->20) 200 with xpDelta | PASS |
| SC06 | same-key replay -> same body | PASS |
| SC07 | same key, different body -> 409 IDEMPOTENCY_KEY_REUSED | PASS |
| SC08 | /xp/balance matches correction result | PASS |
| SC09 | corrected set edited in place (2 sets, reps 20) | PASS |
| SC10 | delete set 200 | PASS |
| SC11 | delete again (new key) -> 404 SET_NOT_FOUND | PASS |
| SC12 | delete last completed set -> 422 SESSION_EMPTY | PASS |
| SC13 | PATCH completion difficulty 200 | PASS |
| SC14 | completedAt later than current -> 400 | PASS |
| SC15 | empty completion body -> 400 | PASS |
| SC16 | other user correct set -> 404 | PASS |
| SC17 | other user PATCH completion -> 404 | PASS |
| SC18 | other user delete set -> 404 | PASS |
| SC19 | other user's XP untouched (0) | PASS |
| SC20 | unauthenticated -> 401 | PASS |

### Plan management — list / pause / resume / archive (#65, #67)

`GET /workout-plans?status=`, `POST …/deactivate` (pause), `POST …/activate {resume}`, `DELETE …` (archive = soft delete).

| # | Check | Prod 2026-10-29 |
|---|---|---|
| PM01 | list (new user) 200, empty | PASS |
| PM02 | list bad status -> 400 | PASS |
| PM03 | save plan 201 | PASS |
| PM04 | activate (no body) 200 isActive | PASS |
| PM05 | list status=active has plan, status active | PASS |
| PM06 | pause (deactivate) 204 | PASS |
| PM07 | pause same-key replay 204 | PASS |
| PM08 | GET /active after pause -> 204 | PASS |
| PM09 | paused plan listed with pausedAt, isActive false, currentWeek null | PASS |
| PM10 | pause again (new key) 204, pausedAt kept | PASS |
| PM11 | activate bad resume type -> 400 | PASS |
| PM12 | resume 200 isActive currentWeek 1 | PASS |
| PM13 | resumed plan pausedAt cleared | PASS |
| PM14 | other user GET plan -> 404 | PASS |
| PM15 | other user pause -> 404 | PASS |
| PM16 | other user activate -> 404 | PASS |
| PM17 | other user archive -> 404 | PASS |
| PM18 | other user list doesn't show A's plan | PASS |
| PM19 | A's plan still active after B's attempts | PASS |
| PM20 | archive active plan 204 | PASS |
| PM21 | GET /active after archive -> 204 | PASS |
| PM22 | default list hides archived | PASS |
| PM23 | status=archived lists it with archivedAt | PASS |
| PM24 | archive again 204, archivedAt kept | PASS |
| PM25 | activate archived -> 409 PLAN_ARCHIVED | PASS |
| PM26 | PATCH archived -> 409 PLAN_ARCHIVED | PASS |
| PM27 | pause on archived: 204 no-op, pausedAt unchanged (archive of the active plan stamps it once) | PASS |

### Nutrition summary (#70)

`GET /nutrition/summary?from&to&bucket=day|week|month` — local (IST) dates, missing days are `null` not 0, max 366 days.

| # | Check | Prod 2026-10-29 |
|---|---|---|
| NS01 | no logs: 200, 3 day points, totals/water null | PASS |
| NS02 | log food 201 | PASS |
| NS03 | day point for today: daysLogged 1, waterMl 750, entryCount 1 | PASS |
| NS04 | yesterday stays null (missing != 0) | PASS |
| NS05 | today's kcal equals the log's kcal | PASS |
| NS06 | week bucket: periods after the first are Mondays, days sum to 14 | PASS |
| NS07 | from > to -> 400 | PASS |
| NS08 | range > 366 days -> 400 | PASS |
| NS09 | bad bucket -> 400 | PASS |
| NS10 | missing from -> 400 | PASS |
| NS11 | other user sees none of A's logs | PASS |
| NS12 | unauthenticated -> 401 | PASS |

### Strength progress (#72)

`GET /progress/exercises?from&to&cursor&limit` and `GET /progress/exercises/{exerciseId}?from&to&bucket` — read-only (no Idempotency-Key, no XP), counting sets only in completed sessions, `pr` all-time and recomputed (corrections lower it), 366 days max for every bucket, other users' sets never counted. Fixture: one session with 100 kg × 5 and 80 kg × 13 → volume 1540, reps 18, top 100, Epley 1RM 116.7 (the 13-rep set gives none).

| # | Check | Prod 2026-10-29 |
|---|---|---|
| ST00 | setup: weighted session (100x5, 80x13) completed | PASS |
| ST01 | list 200: both exercises performed today, timezone echoed | PASS |
| ST02 | list order lastPerformedAt DESC (weighted session first) | PASS |
| ST03 | list pr all-time: maxWeightKg 100, maxReps 13, best1RmKg 116.7 | PASS |
| ST04 | limit=1 gives 1 item + nextCursor | PASS |
| ST05 | page 2 = the other exercise, then no cursor | PASS |
| ST06 | detail day: 3 points, default bucket day, name/trackingType present | PASS |
| ST07 | today: sessions 1, sets 2, volume 1540, reps 18, top 100, 1RM 116.7 (13-rep set no 1RM) | PASS |
| ST08 | empty day: sessions 0, sets 0, volume/reps null; duration/distance null for weighted | PASS |
| ST09 | week bucket: Mondays after the first, first clipped to from | PASS |
| ST10 | PR outside the range still shown (all-time) | PASS |
| ST11 | never-performed exercise: 200, all-null pr | PASS |
| ST12 | unknown exercise -> 404 | PASS |
| ST13 | malformed exercise id -> 404 | PASS |
| ST14 | list from > to -> 400 | PASS |
| ST15 | list missing from -> 400 | PASS |
| ST16 | list bad cursor -> 400 | PASS |
| ST17 | detail 367 days with bucket=month -> 400 | PASS |
| ST18 | detail 366 days with bucket=month -> 200 | PASS |
| ST19 | detail bad bucket -> 400 | PASS |
| ST20 | detail bad date -> 400 | PASS |
| ST21 | correction 100->50 kg lowers the record to 80 (recomputed) | PASS |
| ST22 | progress reads don't change XP | PASS |
| ST23 | other user list: 200, no items | PASS |
| ST24 | other user detail of same exercise: 0 sessions, null pr (no leak of A's sets) | PASS |
| ST25 | list unauthenticated -> 401 | PASS |
| ST26 | detail unauthenticated -> 401 | PASS |

### Macro targets (#85)

`dailyProteinTargetG` / `dailyCarbsTargetG` / `dailyFatTargetG` on `PUT/GET /me/profile` (integer 0–1000 or null; omitted keeps, null clears, 0 stored as null; no derivation from kcal) and wired into `/nutrition/summary` (`targets.*G`, independent `*Pct`, HALF_UP from the unrounded average, current targets for every period) and `/me/export`.

| # | Check | Prod 2026-10-29 |
|---|---|---|
| MT01 | new profile: macro targets null | PASS |
| MT02 | PUT sets all three (200, echoed) | PASS |
| MT03 | GET returns them | PASS |
| MT04 | explicit null clears carbs, others kept | PASS |
| MT05 | PUT 0 -> 200, returned as null | PASS |
| MT06 | GET after 0: null, protein kept | PASS |
| MT07 | protein -1 -> 400 | PASS |
| MT07 | protein 1001 -> 400 | PASS |
| MT07 | protein 12.5 -> 400 | PASS |
| MT07 | protein "50" -> 400 | PASS |
| MT08 | 1000 accepted | PASS |
| MT09 | invalid PUTs changed nothing (protein 120) | PASS |
| MT10 | setting kcal target doesn't derive macros | PASS |
| MT11 | summary targets: protein 120, carbs/fat null, non-null with only a macro set | PASS |
| MT12 | today: proteinPct = protein/120*100 (1 dp), carbs/fat pct null | PASS |
| MT13 | yesterday (no food): adherence null | PASS |
| MT14 | current targets apply to the whole range (month bucket echoes protein 120) | PASS |
| MT15 | export includes macro targets | PASS |
| MT16 | other user unaffected (null targets) | PASS |
| MT17 | other user summary targets null | PASS |
| MT18 | unauthenticated PUT -> 401 | PASS |

Notes:
- Archiving the **active** plan pauses it first, so it gets `pausedAt` at archive time (contract: "same effect as pause"); a later pause on the archived plan leaves `pausedAt` unchanged (PM27).
- Sets logged with `POST /workout-sessions/{id}/sets` need an `Idempotency-Key`; without one the session stays empty and `complete` returns 422.

### Progression suggestions (#90)

`GET /workout-plans/{planId}/progression-suggestions` (docs/specs/adaptive-progression.md): read-only, rule-based, window of the newest 3 completed sessions per exercise in the plan. A custom plan (W1: 12 exercises covering reps_weight upper/lower body, off-grid 40.3 kg, no-target, reps, duration, distance_duration; W2: bench at 40 kg and push-up at 19–20 reps) gets 3 W1 sessions (difficulty 3, 2, 3) with planned misses (lat pulldown once; tricep, squat, superman in the last two). A second plan checks effort (difficulty 5, 5, then 3). B is the other user.

| # | Check | Prod 2026-10-29 |
|---|---|---|
| PG01 | empty history: 200, planVersion, 14 suggestions, window 3 | PASS |
| PG02 | all hold NOT_ENOUGH_HISTORY, order 0..11,0,1 | PASS |
| PG03 | two sessions: still NOT_ENOUGH_HISTORY (BP, LP) | PASS |
| PG04 | BP (chest 40) -> increase 41 | PASS |
| PG05 | LP (quads 100, 5%) -> increase 105 | PASS |
| PG06 | BC (8 kg, min step 0.5) -> 8.5 | PASS |
| PG07 | SPR (40.3 off-grid) -> 41.5 | PASS |
| PG08 | ROW no target -> increase NO_TARGET_SET 22 (heaviest 22.3 floored) | PASS |
| PG09 | PU W1 (8-12 reps) -> 9-13 | PASS |
| PG10 | PU W2 (19-20) -> hold REP_CEILING with harderVariations array | PASS |
| PG11 | PL 60 s -> 65 | PASS |
| PG12 | WALK 300 s -> 330 | PASS |
| PG13 | LAT missed once -> hold MISSED_TARGET | PASS |
| PG14 | TRI missed twice -> deload REPEATED_MISSES 27 | PASS |
| PG15 | SUP missed twice -> deload 55 s | PASS |
| PG16 | SQ (reps) missed twice -> hold REPEATED_MISSES, no suggestion | PASS |
| PG17 | basedOn: 3 sessions newest first, BP met, best 40/10, WALK distance 2000 | PASS |
| PG18 | all suggested weights on the 0.5 kg grid, no cooldownUntil yet | PASS |
| PG19 | GET is read-only: plan version and XP unchanged | PASS |
| PG20 | accept BP/ROW/PU (W1) with PATCH baseVersion -> version+1 | PASS |
| PG21 | BP W1 -> hold COOLDOWN, cooldownUntil ~ PATCH + 7 d | PASS |
| PG22 | PU W1 (9-13 accepted) -> COOLDOWN | PASS |
| PG23 | BP W2 (not raised) still increase 41, same basedOn | PASS |
| PG24 | ROW 22 (target set from null: not a raise) -> increase 23, no cooldown | PASS |
| PG25 | LP untouched still increase 105, planVersion = new version | PASS |
| PG26 | PATCH stale baseVersion -> 409 VERSION_CONFLICT | PASS |
| PG27 | two very hard sessions: GOB -> deload VERY_HARD 18 | PASS |
| PG28 | GB (reps) -> hold VERY_HARD, no suggestion | PASS |
| PG29 | third session at 3 (window has a 5): GOB and GB hold HIGH_EFFORT | PASS |
| PG30 | unknown plan id -> 404 | PASS |
| PG31 | malformed plan id -> 404 | PASS |
| PG32 | other user -> 404 | PASS |
| PG33 | other user starts session on A's planned workout -> 404 | PASS |
| PG34 | B's own plan (same exercise): no history from A | PASS |
| PG35 | archived plan -> 409 PLAN_ARCHIVED | PASS |
| PG36 | other user on archived plan -> 404 (not 409) | PASS |
| PG37 | unauthenticated -> 401 | PASS |

Notes: a PATCH creates a new plan version with new planned-workout ids, so suggestions after an accepted change are matched by the new ids (PG21–25). Cooldown is per (workout, exercise position): setting a target on a no-target entry is not a raise (PG24).
