# Deploy: Render Free + Supabase Free (T17)

This is config only. Nothing here creates resources; Vipin runs both dashboards. Both plans are free. **Any paid upgrade needs Vipin's approval.**

## 1. Supabase (database)
1. Create a project on the **Free** plan, in a region near Render's (e.g. Singapore, `ap-southeast-1`). Keep the DB password in a password manager.
2. Project → **Connect** → **Session pooler** (port **5432**, IPv4). Copy the URI:
   `postgresql://postgres.<project-ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres`
   - Use the session pooler for **both** `DATABASE_URL` and `DIRECT_URL`. The direct host (`db.<ref>.supabase.co`) is IPv6-only, which Render can't reach, and the transaction pooler (6543) doesn't suit `prisma migrate`.
   - URL-encode special characters in the password.
3. Free-plan caveat: the project **pauses after 7 days with no activity**; restore it from the dashboard. `/health` doesn't touch the DB, so Render won't restart-loop while it's paused; `/health?db=true` reports `db: "down"`.

## 2. Render (API)
1. Render → **New → Blueprint** → `vipinsuper19/fitness`, branch `main`. It reads `render.yaml` (web service `fitness-api`, plan **free**, root dir `backend`).
2. Enter the `sync: false` values when prompted:

| Key | Required | Value |
|-----|----------|-------|
| `DATABASE_URL` | yes | Supabase session pooler URI (port 5432) |
| `DIRECT_URL` | recommended | same URI; used by `prisma migrate deploy` |
| `JWT_ACCESS_SECRET` | yes | `openssl rand -base64 48` (min 32 chars) |
| `JWT_REFRESH_SECRET` | yes | a different `openssl rand -base64 48` |
| `CORS_ORIGINS` | optional | leave empty (the Android app doesn't need CORS) |

   Preset in `render.yaml`: `NODE_VERSION=22`, `NODE_ENV=production`, `TRUST_PROXY_HOPS=3`, `SWAGGER_ENABLED=false`.

   `TRUST_PROXY_HOPS=3`: Express counts the 127.0.0.1 socket and Render's internal 10.x proxy as the first two hops, and Cloudflare's edge as the third (confirmed in production logs on Oct 21). Express has to skip all three to get the real client IP, which per-IP rate limiting needs. **Warning:** Do not set it higher: extra hops make forged left-hand X-Forwarded-For entries trusted, so clients could spoof their IP and dodge per-IP rate limits. After the first deploy, check `req.ip`. Eleven quick `POST /auth/login` calls should get a `429` on the 11th, while other networks are unaffected.

   Optional overrides; defaults come from `backend/src/config/env.ts`:
   - `JWT_ACCESS_TTL_SECONDS` (900), `JWT_REFRESH_TTL_DAYS` (30)
   - `AUTH_RATE_LIMIT_PER_MINUTE` (10, per IP: register, login, logout, `DELETE /me`, `POST /workout-plans/generate`), `AUTH_REFRESH_RATE_LIMIT_PER_MINUTE` (30), `LOGIN_MAX_FAILURES_PER_ACCOUNT` (5)
   - `TARGET_HISTORY_RATE_LIMIT_PER_MINUTE` (60, per user)
   - `NOTIFICATION_PREFS_RATE_LIMIT_PER_MINUTE` (60, per user, for each of GET and PATCH `/me/notification-preferences`)
   - Per-user write limits (requests per minute; every write route has exactly one named throttle, see `backend/src/common/rate-limit.ts` and `test/rate-limits.spec.ts`): `PROFILE_RATE_LIMIT_PER_MINUTE` (60, PUT `/me/profile`), `GOALS_RATE_LIMIT_PER_MINUTE` (60, goal writes), `PLANS_RATE_LIMIT_PER_MINUTE` (60, plan writes and `GET /workout-plans/{planId}/progression-suggestions`; `POST /workout-plans/generate` stays on the per-IP `AUTH_RATE_LIMIT_PER_MINUTE`), `WORKOUT_SESSIONS_RATE_LIMIT_PER_MINUTE` (300, session, set and correction writes), `NUTRITION_RATE_LIMIT_PER_MINUTE` (300, food, food-log and water-log writes), `BODY_MEASUREMENTS_RATE_LIMIT_PER_MINUTE` (60), `CHALLENGES_RATE_LIMIT_PER_MINUTE` (60, join and leave). Counters are in memory per instance; a 429 returns `RATE_LIMITED` with `Retry-After`.
   - `HEALTH_DEEP_RATE_LIMIT_PER_MINUTE` (12, per IP): `GET /health/deep`, which queries the database.
   - `NOTIFICATION_DEVICES_RATE_LIMIT_PER_MINUTE` (30, per user, for each of POST and DELETE `/me/notification-devices`), `NOTIFICATIONS_TICK_MAX_ROWS` (500)
   - `EXPORT_RATE_LIMIT_PER_HOUR` (5), `EXPORT_MAX_ROWS` (5000), `EXPORT_MAX_SESSIONS` (2000), `EXPORT_MAX_PLAN_VERSIONS` (200), `EXPORT_MAX_SETS_TOTAL` (20000), `EXPORT_MAX_PLAN_EXERCISES_TOTAL` (10000)
   - `PORT` is set by Render.
3. Build: `npm ci --include=dev && npm run build` (`prisma generate` + `tsc`).
   Start: `npm run migrate:deploy && exec node dist/main.js` (run from root dir `backend`; same as `npm start`, but `exec` makes node the process that receives `SIGTERM`). Migrations are idempotent, and the Free plan has no pre-deploy step.
4. `autoDeploy` is on: **every merge to main that touches `backend/**` or `render.yaml` deploys to Render automatically.** Android-only and docs-only merges don't (Render `buildFilter`).
   - The `post-deploy-smoke` workflow then runs automatically on the same pushes. It waits up to ~6 min for the service to restart (`/health` uptime lower than the time since the push), then runs the smoke test (health + DB, register → generate a plan with ≥ 1 workout → delete account → login fails, test user always cleaned up). The generate step fails until the exercise catalog is seeded (step 5), so seed before relying on it.
   - If no restart is seen in time, the workflow **fails** rather than smoke-testing the old build: check the deploy in the Render dashboard. A free instance waking from spin-down also resets uptime, which can let the wait pass early; the smoke then still checks the live service.
   - Manual run: Actions → post-deploy-smoke → Run workflow (`skip_wait` defaults to true).
5. Seed the catalogs (exercises and Indian foods). It's idempotent (upserts on fixed ids), never touches user data, and is safe to re-run. Render Free has no shell, so either:
   - **GitHub Actions (recommended):** add the repository secret `FITNESS_DATABASE_URL` (Settings → Secrets and variables → Actions) with the Supabase session pooler URI (port 5432). Then go to Actions → **db-seed** → Run workflow. It prints the exercise and food counts before and after the seed, and never prints the URL.
   - **Locally:** `cd backend && DATABASE_URL=... DIRECT_URL=... npm run db:seed`. This needs outbound access to port 5432.
6. **Release backfills (manual, one-off).** Render runs only `migrate:deploy` on start; there is no post-deploy hook on Free, and none of these run automatically.
   - **Target history** ([target-history.md](specs/target-history.md)). Order: **migrate → deploy → backfill**.
     1. The migration `20261029001200_profile_target_history` runs on start (`migrate:deploy`) and already does the idempotent `1900-01-01` backfill.
     2. The new app goes live; wait for `post-deploy-smoke` to pass for that commit.
     3. Run the reconcile once: Actions → **targets-backfill** → Run workflow (same `FITNESS_DATABASE_URL` secret as db-seed), or locally `cd backend && DATABASE_URL=... npm run targets:backfill`. It catches target changes the old instance accepted during the deploy window. Re-running is a no-op.
     - **After any redeploy that follows a rollback, run it again** (the old app doesn't write history).
   - **Badges:** `DATABASE_URL=... npm run badges:backfill`, locally.
7. **Purge expired rows (manual; dry run by default).** Actions → **purge-expired** → Run workflow (same `FITNESS_DATABASE_URL` secret), or locally `cd backend && DATABASE_URL=... npm run purge:expired` (add `-- --execute` to delete).
   - Deletes `idempotency_keys` past their 24 h TTL, and `refresh_tokens` revoked or expired more than 7 days ago **only in families with no live token**. A revoked token in a family that still has a live one is kept, because presenting it is how token reuse (theft) is detected and the family revoked.
   - Without `execute` (the default) it only prints per-table counts and cutoffs. It never prints tokens, hashes or the URL. Deletes run in batches of 1000, and it is idempotent.
   - No schedule; it never runs automatically. **Running it with `execute: true` against production needs Vipin's OK.** Do a dry run first.

8. **Push notifications (planned, [notification-sending.md](specs/notification-sending.md); not implemented yet).** Secrets go in Render env vars only, never in the repo or logs:
   | Key | Required | Value |
   |---|---|---|
   | `NOTIFICATIONS_SENDING_ENABLED` | optional | `false` (default) until Vipin turns sending on |
   | `FCM_PROJECT_ID` | for sending | Firebase project id (free Spark plan) |
   | `FCM_SERVICE_ACCOUNT_JSON` | for sending | service-account key JSON on one line (role: Firebase Cloud Messaging API Admin) |
   | `NOTIFICATIONS_CRON_SECRET` | for the wake call | `openssl rand -base64 48`; unset = tick endpoint off (404) |

   If the GitHub Actions wake is chosen, add repository secrets `NOTIFICATIONS_CRON_SECRET` (same value) and `FITNESS_API_BASE_URL`. The wake mechanism and creating the Firebase project and service account are Vipin's decisions.

## 3. Verify
- `curl https://<service>.onrender.com/health` → `{"status":"ok",...}`
- `curl 'https://<service>.onrender.com/health?db=true'` → `"db":"up"`
- `curl -i https://<service>.onrender.com/health/deep` → `200 {"status":"ok","db":"up","migration":"<latest migration>"}`; the migration should be the newest folder in `backend/prisma/migrations`. `503` with `"db":"down"` means the database is unreachable or slower than ~2 s (e.g. Supabase paused). Use it for manual checks and external monitors, **not** as Render's health check path (keep `/health`, so a paused DB doesn't restart-loop the service). Spec: [docs/specs/health-deep.md](specs/health-deep.md).
- API base: `https://<service>.onrender.com/api/v1/`

## 4. Android staging URL
Once the service exists, set the Android **debug** build's API base URL to `https://<service>.onrender.com/api/v1/`. No Play upload.

## Request log
Every request writes one JSON line to stdout (Render → Logs), for example:

```json
{"type":"request","requestId":"6f1c…","method":"GET","route":"/api/v1/goals/{goalId}","status":404,"durationMs":12.3,"userId":"9b2e…"}
```

- `/health`, `/health?db=true` and `/health/deep` are not logged (health-check noise).
- `route` is the route **template**, never the raw path or query string; unmatched paths log `route: null`.
- `userId` is the opaque account UUID, only on authenticated requests.
- **Never logged:** emails, request/response bodies, query strings, tokens, `Authorization`/cookie headers, IPs (not hashed either; rate-limit debugging uses the route, status and `requestId`), user agent.
- `requestId` equals the `X-Request-Id` response header and `requestId` in error bodies; ask users for it in bug reports and search the logs for it.
- `REQUEST_LOG=off` disables the line (default `on`; tests set `off`). Nest's existing logger and its default levels are unchanged.
- Spec: [docs/specs/request-log.md](specs/request-log.md).

## Graceful shutdown
Render sends `SIGTERM` to the old instance (60 s after switching traffic to the new one on a deploy) and `SIGKILL` if the process hasn't exited within the service's shutdown delay, **30 s by default** (`maxShutdownDelaySeconds`, 1–300; [Render: Deploys → Graceful shutdown](https://render.com/docs/deploys#graceful-shutdown), [Blueprint spec](https://render.com/docs/blueprint-spec)). The API handles it in `backend/src/common/graceful-shutdown.ts`:

1. Stop accepting new connections (`server.close()`), close idle keep-alive sockets, and send `Connection: close` on any response still going out.
2. Let in-flight requests finish for up to `SHUTDOWN_GRACE_MS` (default `10000`, max `25000`, so it stays well under Render's 30 s); after that the remaining sockets are closed and a warning is logged.
3. Run Nest's shutdown hooks (`app.close()`): Prisma `$disconnect` and timers, only **after** the last response.
4. Exit `0`.

Nest's `enableShutdownHooks()` is deliberately not used: it disconnects Prisma while requests are still running and re-raises the signal (exit 143). `/health` doesn't report "shutting down": once `SIGTERM` arrives Render has already moved traffic and health checks to the new instance, and the closed listener refuses new connections, so a status would never be seen; `/health`'s contract stays unchanged.

**Signal delivery:** `startCommand` is `npm run migrate:deploy && exec node dist/main.js`. `exec` replaces the `sh` that Render starts with `node` itself, so node is the process that receives Render's `SIGTERM` and the handler above runs. With the old `... && npm start`, `sh` (and `npm`) stayed as the parent and the signal might never reach node, ending in `SIGKILL` / exit 137. After a deploy, the old instance's logs should show `shutdown complete`, not exit 137. If the Render service's start command is set in the dashboard rather than synced from this Blueprint, it must be changed there to match.

## Free-tier notes
- **Cold starts (accepted):** Render Free spins down after ~15 min idle, so the first request after that takes **about 50 s**. **No keep-alive pinger is used**, by choice, to stay within the free plan; the app should show a loading state and retry.
- Render Free has 512 MB RAM. argon2 (19 MiB per hash) fits.
- `backend/Dockerfile` is an alternative (runtime `docker`). Since #17, `prisma` is a runtime dependency, so the Docker image keeps the Prisma CLI and `npm run migrate:deploy` works inside it. The Blueprint uses the native Node runtime anyway, to keep the build simple.
- Node 22 is required (`engines: >=22`). argon2's native build fails on Node 20.
