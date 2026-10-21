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
