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
| `AUTH_RATE_LIMIT_PER_MINUTE` | per client IP on `/auth/*` and `/me`; default 10 |
| `CORS_ORIGINS`, `SWAGGER_ENABLED` | optional |

Secrets are set in the Render and Supabase dashboards only, never in the repo. There is no AI provider or key; AI features use rule-based fallbacks.

## Health
- `GET /health` returns `{ status: "ok", uptimeSeconds }` without touching the database, so a paused Supabase project does not restart-loop the Render service.
- `GET /health?db=true` also probes the database and reports `db: "up" | "down"`.

## Docker / Render
`docker build -t fitness-api backend/` produces a non-root Node 22 image that runs `node dist/main.js`. On Render: Docker runtime, root directory `backend`, health check path `/health`. Migrations run with `npm run migrate:deploy` against `DIRECT_URL`.

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
- **Access token:** our own HS256 JWT (`JWT_ACCESS_SECRET`, 15 minutes, `iss=fitness-api`, `aud=fitness-app`). The global `JwtAuthGuard` protects every route except `@Public()` ones (health, register, login, refresh).
- **Refresh token:** an opaque 256-bit value. Only its SHA-256 is stored in `refresh_tokens`.
  - Each refresh revokes the presented token and issues a new one in the same family; the claim is atomic, so of two concurrent refreshes only one wins.
  - Presenting a revoked token revokes the whole family (`REFRESH_TOKEN_REUSED`).
  - Logout revokes the caller's own token.
- **Register idempotency (contract):**
  - `Idempotency-Key` only blocks a concurrent duplicate (`409 IDEMPOTENCY_IN_PROGRESS`).
  - Tokens are never stored, so a retry returns `409 EMAIL_TAKEN` or fresh tokens.
  - The password is not part of the stored request hash.
- **`DELETE /me`:** requires the password (`403 INVALID_PASSWORD` otherwise), then hard-deletes the account and everything it owns.
- **Hardening:**
  - helmet headers, `X-Request-Id`
  - a 100 kb JSON body limit (`413 PAYLOAD_TOO_LARGE`)
  - a global `ValidationPipe` (whitelist, unknown fields rejected; errors are `400 VALIDATION_FAILED` with `details.fields`)
  - one error shape everywhere
  - throttling on auth routes (`429 RATE_LIMITED` with `Retry-After`)
  - `trust proxy` set to one hop for Render
