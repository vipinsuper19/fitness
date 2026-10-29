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

Run: `scripts/prod-check-p1.sh` (or `API=… scripts/prod-check-p1.sh`). Uses two throwaway accounts (A owner, B other user) with the `prod-probe.sh` safety pattern: credentials only in mode-600 temp files, never printed or in argv; the EXIT trap deletes both accounts and confirms login → 401.

**Last prod run: 2026-10-29 ~11:00 IST — 59/59 PASS, both accounts deleted (login 401).**

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

Notes:
- Archiving the **active** plan pauses it first, so it gets `pausedAt` at archive time (contract: "same effect as pause"); a later pause on the archived plan leaves `pausedAt` unchanged (PM27).
- Sets logged with `POST /workout-sessions/{id}/sets` need an `Idempotency-Key`; without one the session stays empty and `complete` returns 422.
