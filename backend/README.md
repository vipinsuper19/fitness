# backend/

Fitness API: NestJS 11 + TypeScript, Prisma 7 (pg driver adapter) on PostgreSQL (Supabase Free), Docker on Render Free. API base path `/api/v1`; `GET /health` stays at the root for Render's health check, and Swagger UI is at `/docs` outside production.

## Local
```bash
cd backend
cp .env.example .env        # fill in local values; .env is git-ignored
npm ci
npm run lint && npm run typecheck && npm test
npm run build && npm start  # http://localhost:3000/health
```

## Environment
Validated at startup by `src/config/env.ts` (zod). The app refuses to start on a missing or invalid value, and errors name the key, never the value.

| Key | Notes |
|-----|-------|
| `DATABASE_URL` | Supabase pooled connection (port 6543) used by the app |
| `DIRECT_URL` | Supabase direct connection (port 5432) used by `prisma migrate` |
| `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET` | 32+ random chars each; our own JWTs (T7) |
| `JWT_ACCESS_TTL_SECONDS`, `JWT_REFRESH_TTL_DAYS` | default 900 s and 30 days |
| `PORT` | set by Render; default 3000 |
| `AUTH_RATE_LIMIT_PER_MINUTE` | per client IP on `/auth/*` (except refresh) and `/me`; default 10 |
| `AUTH_REFRESH_RATE_LIMIT_PER_MINUTE` | per client IP on `/auth/refresh`; default 30 |
| `LOGIN_MAX_FAILURES_PER_ACCOUNT` | failed logins per email per 15 min before 429; default 5 (in-memory, per instance) |
| `TRUST_PROXY_HOPS` | Express `trust proxy`; default 1 (Render's guidance) |
| `CORS_ORIGINS`, `SWAGGER_ENABLED` | optional |

Secrets are set in the Render and Supabase dashboards only, never in the repo. There is no AI provider or key; AI features use rule-based fallbacks.

## Health
- `GET /health` returns `{ status: "ok", uptimeSeconds }` without touching the database, so a paused Supabase project does not restart-loop the Render service.
- `GET /health?db=true` also probes the database and reports `db: "up" | "down"`.

## Deploy
### Render (native Node 22, per `render.yaml`)
The Blueprint in `render.yaml` defines the `fitness-api` web service: Free plan, Singapore region, root directory `backend`, health check `/health`. `autoDeploy` is off, so deploy with **Manual Deploy**. The step-by-step guide is in `docs/DEPLOY.md`. Both files come from DevOps PR #14, which isn't merged yet.
- **Build:** `npm ci --include=dev --no-audit --no-fund && npm run build`, which runs `prisma generate` and `tsc`.
- **Start:** `npm run migrate:deploy && npm start`. The Free plan has no pre-deploy step, so pending migrations are applied on every start. This is safe to repeat.
- **Preset env:** `NODE_VERSION=22`, `NODE_ENV=production`, `TRUST_PROXY_HOPS=1` (Render puts one proxy in front of the app), `SWAGGER_ENABLED=false`.
- **Entered in the dashboard (`sync: false`):**
  - `DATABASE_URL` and `DIRECT_URL`: both the Supabase session pooler URI, port 5432 (IPv4).
  - `JWT_ACCESS_SECRET` and `JWT_REFRESH_SECRET`: different values, at least 32 characters each (`openssl rand -base64 48`).
  - `CORS_ORIGINS`: optional; leave empty for the Android app.
- **Optional overrides** (defaults in `src/config/env.ts`): the JWT TTLs, the auth, refresh and login-failure limits, and the `EXPORT_*` rate limit and caps.
- **Seed (optional, once):** Render Free has no shell, so run `npm run db:seed` locally against the same URLs.
- **After the first deploy, check:**
  - `curl https://<service>.onrender.com/health` returns `"status":"ok"`.
  - `curl 'https://<service>.onrender.com/health?db=true'` returns `"db":"up"`, which also confirms the migrations ran.
  - **`req.ip`:** confirm the app sees your real client address, not Render's proxy, so per-IP throttling works. Send 11 quick `POST /auth/login` requests from one machine: the 11th should return `429`, and requests from another network should still succeed. If they don't, adjust `TRUST_PROXY_HOPS`.

### Docker (alternative, and what CI builds)
`docker build -t fitness-api backend/` produces a non-root Node 22 image that runs `node dist/main.js`.
- **Migrations:** `prisma` is a runtime dependency, so the pruned image keeps the CLI and you can run `docker run --rm -e DATABASE_URL -e DIRECT_URL fitness-api npm run migrate:deploy`. CI runs exactly that against its Postgres.
- **Size:** keeping the CLI costs nothing; `@prisma/client` already pulled it in as a peer, and the pruned `node_modules` is 426 MB either way.
- **On Render's Docker runtime:** use the same env vars and run migrations before `node dist/main.js`.

## Database (T6)
- Schema: `prisma/schema.prisma`. MVP entities are User, RefreshToken, UserProfile, FitnessGoal, Exercise, WorkoutPlan (+ WorkoutPlanVersion), PlannedWorkout, PlannedExercise, WorkoutSession, ExerciseSetLog, FoodItem, FoodLog, WaterLog, BodyMeasurement, XPTransaction and IdempotencyKey.
- Every user-owned row cascades on account deletion. A user has at most one active plan (`user_profiles.active_plan_id` is unique).
- XP is server-only. `xp_transactions` is unique on `(user_id, source_type, source_id)`, so a retried award is a no-op. A reversal is a negative `adjustment` row.
- Retry safety: set logs are unique on `(user_id, client_set_id)`. `src/idempotency/idempotency.service.ts` is insert-first:
  - It inserts an `in_progress` row keyed on `(scope, key)` before the handler runs. A concurrent duplicate hits the unique index and gets `409 IDEMPOTENCY_IN_PROGRESS`.
  - A finished request with the same method, path and body hash is replayed. Anything else is `409 IDEMPOTENCY_KEY_REUSED`.
  - Token-bearing responses (auth) are never stored; a database CHECK enforces this too. A sign-up retry re-runs the handler.
  - Sign-up rows get `user_id` once the account exists, so they are erased with it.
  - Expired rows are purged hourly.
- `food_logs.food_id` is `ON DELETE NO ACTION`. It's checked at the end of the statement, so the account-deletion cascade can remove custom foods together with their logs, while deleting a logged food on its own still fails.
- Migrations: `prisma/migrations/` (the Prisma init migration, plus SQL CHECK constraints that mirror the contract ranges).
  - Apply: `npm run migrate:deploy` (uses `DIRECT_URL` when set).
  - Drift check: `npm run migrate:check` (compares the migrated DB with `schema.prisma`).
- Seed: `npm run db:seed` upserts 32 exercises (home-first) and 44 Indian-first foods in household servings (katori, plate, glass) with deterministic UUIDv5 ids. It is safe to re-run and never touches user data. The nutrition values are typical approximations, and users can edit them on log entries.
- DB tests: `DB_TESTS=1 npm test` against a migrated database (CI does this with its Postgres service).

## Auth (T7)
- **Register and login:** email and password, hashed with argon2id (19 MiB, t=2, p=1). Emails are trimmed and lower-cased. An unknown email and a wrong password return the same `401 INVALID_CREDENTIALS` after the same hashing work.
- **Access token:** our own HS256 JWT (`JWT_ACCESS_SECRET`, 15 minutes, `iss=fitness-api`, `aud=fitness-app`). The global `JwtAuthGuard` protects every route except `@Public()` ones (health, register, login, refresh), and also checks the user still exists, so a deleted account's unexpired token gets `401 UNAUTHENTICATED`.
- **Refresh token:** an opaque 256-bit value. Only its SHA-256 is stored in `refresh_tokens`.
  - Each refresh claims the presented token, issues its successor and links them in one transaction; of two concurrent refreshes only one wins. There is no grace window.
  - Presenting a revoked token revokes the whole family (`REFRESH_TOKEN_REUSED`).
  - Logout revokes the caller's own token.
- **Register idempotency (contract):**
  - `Idempotency-Key` only blocks a concurrent duplicate (`409 IDEMPOTENCY_IN_PROGRESS`).
  - Tokens are never stored, so a retry returns `409 EMAIL_TAKEN` or fresh tokens.
  - The password is not part of the stored request hash.
- **`DELETE /me`:** requires the password (`403 INVALID_PASSWORD` otherwise), then revokes every refresh token and hard-deletes the account and everything it owns, in one transaction.
- **Hardening:**
  - helmet headers, `X-Request-Id`
  - a 100 kb JSON body limit (`413 PAYLOAD_TOO_LARGE`)
  - a global `ValidationPipe` (whitelist, unknown fields rejected; errors are `400 VALIDATION_FAILED` with `details.fields`)
  - one error shape everywhere
  - throttling on auth routes (`429 RATE_LIMITED` with `Retry-After`), a looser bucket for refresh, and a per-account cap on failed logins
  - `trust proxy` set to `TRUST_PROXY_HOPS` (1, per Render's docs); verify `req.ip` after the first deploy

## Profile, goals, exercises and workout plans (T10)
- **`GET/PUT /me/profile`:**
  - metric only (`unitSystem` is a display preference); India-first defaults (`Asia/Kolkata`, `en-IN`, INR)
  - an omitted field is left unchanged and `null` clears it, so onboarding can be skipped and resumed
  - height is rounded to 0.1 cm and weight to 0.01 kg
- **`/goals` CRUD:** owner-scoped; another user's id is `404`. Weight targets must be 20–400 kg and `workouts_per_week` 1–7.
- **`/exercises`:** the seeded catalog, read-only.
  - search by English or Hindi name, by muscle (primary or secondary) and by equipment
  - keyset pagination with an opaque `cursor`
- **`/workout-plans`:**
  - `generate` is rule-based (`src/workout-plans/generator.ts`, no AI) and pure: it never persists, and the same input gives the same draft
  - `POST` saves version 1
  - `PATCH` writes `baseVersion + 1`, using compare-and-set; a stale base gets `409 VERSION_CONFLICT`, and older versions stay readable through `?version=`
  - `activate` sets the single `user_profiles.active_plan_id`
- **Retries:** every write here takes an optional `Idempotency-Key`, handled by `IdempotentWriteService`. A replay returns the stored status and body with `Idempotent-Replayed: true`. A different body, method or path is `409 IDEMPOTENCY_KEY_REUSED`.
- **XP:** nothing here awards XP; that comes with session completion in T12.

## Workout sessions and XP (T12)
- **Start:** `POST /workout-sessions` (Idempotency-Key required).
  - Can start from one of your own planned workouts; the session records the plan version it followed.
  - Offline start times are accepted but must not be in the future (5 minutes of clock skew allowed).
- **Sets:** `POST /workout-sessions/{id}/sets` upserts by the device's `clientSetId`.
  - Re-sending a `clientSetId` replaces that set.
  - Using a `clientSetId` in another session is `409 CLIENT_SET_CONFLICT`.
- **Pause and resume:** `PATCH` pauses or resumes. `activeSeconds` counts only running time (`active_since` marks the running segment) and is capped at 24 h.
- **Complete:** `POST /{id}/complete` is the only call that awards XP. It runs in one transaction under a row lock, writing the award with `sourceId` = session id.
  - Rule: 20 base + 2 per completed set (max 30 sets) + 1 per 5 active minutes (max 20), capped at 100.
  - A session with no completed sets is `422 SESSION_EMPTY`.
  - Completing again returns the original `xpAwarded`, and completed sessions are read-only (`409 SESSION_COMPLETED`).
- **Discard:** `DELETE /workout-sessions/{id}` is the correction path.
  - A completed session's award is reversed by one `adjustment` whose `sourceId` is the award.
  - The award stays in the ledger, so the session can never be awarded again.
- **History:** `GET /workout-sessions/history` lists completed sessions, newest first. `from` and `to` are local dates in the profile's timezone (IST by default). Cursors are validated.
- **XP:** `GET /xp/balance` and `GET /xp/ledger` are read-only. Reaching level n+1 from level n takes 100 × n XP.
- **Local testing:** the concurrency tests (row locks, parallel completion) need a real Postgres. PGlite shares one session across connections, so use CI's Postgres service or a local Postgres 16.

## Nutrition (T14, backend)
- **Food search:** `GET /foods/search?query` covers the seeded Indian-first catalog plus your own custom foods, by English or Hindi name, brand or slug. Cursors are validated.
- **Custom foods:** `POST /foods` creates one, visible only to you.
- **Food log** (`/food-logs`):
  - Nutrients are the food's per-serving values × servings, rounded to 0.1.
  - `nutrientsOverride` stores your own totals (`nutrientsEdited: true`) and survives servings edits; `null` restores the computed values.
  - The day is a local calendar date.
- **Water:** `/water-logs` stores millilitres, 1–5000 per entry.
- **Daily totals:** `GET /nutrition/daily?date` totals the day and each meal against the profile targets. An optional nutrient (fibre, sugar, sodium) stays `null` unless some entry has it.
- **Limits:** input nutrients are capped per serving (5000 kcal, 900 g per macro, 9000 mg sodium), so 100 servings fit the diary columns.
- **Retries:** creating a food log or water log requires an Idempotency-Key; other writes take one optionally.
- **Ownership:** another user's entries are 404.
- **Offline session starts:** `POST /workout-sessions` rejects a `startedAt` more than 24 h old.

## Progress (T15, backend)
- **Body measurements:** `/body-measurements` CRUD, owner-only.
  - Each entry needs a date and at least one measurement (kg, %, cm).
  - `PATCH` is a partial update: a missing field stays unchanged, `null` clears it, and at least one measurement must remain (else 400).
  - The list is newest first, with validated `{date, id}` cursors.
- **Progress summary:** `GET /progress/summary?from&to` returns one point per local day (profile timezone), for at most 366 days. `null` means no data, never 0.
  - **weight:** the day's latest measured weight.
  - **energyKcal:** the day's logged kcal.
  - **workoutsCompleted / activeMinutes:** completed sessions by local completion day.
  - **xpEarned:** awards that day that weren't later reversed.
- **Streak:** consecutive local days with a completed workout, ending today. If there's nothing yet today, the streak ending yesterday is returned.
- **Daily nutrition:** `GET /nutrition/daily` now reports `entryCount` and `entriesMissing` (per optional nutrient), so a partial fibre, sugar or sodium total is visible.

## Data export (T7 follow-up)
- `GET /me/export` returns only the signed-in user's own data: user, profile, goals, every plan version, sessions with set logs, custom foods, food and water logs, body measurements and the XP ledger.
- It never includes password hashes, refresh tokens or idempotency records, because every collection goes through an explicit view.
- Bounded, newest-first, with queries run one after another:
  - `EXPORT_MAX_ROWS`: 5000 rows per collection
  - `EXPORT_MAX_SESSIONS`: 2000 sessions
  - `EXPORT_MAX_PLAN_VERSIONS`: 200 plan versions
  - `EXPORT_MAX_SETS_TOTAL`: 20000 set logs in total
  - `EXPORT_MAX_PLAN_EXERCISES_TOTAL`: 10000 planned exercises in total
- Anything cut is listed in `truncated`. The nested caps are reported as `workoutSessions.sets` and `workoutPlans.exercises`.
- Worst case is roughly 40k rows and about 15–20 MB of JSON, under about 100 MB of peak heap.
- Rate-limited per user: `EXPORT_RATE_LIMIT_PER_HOUR`, default 5. The response is sent with `Cache-Control: no-store`.
