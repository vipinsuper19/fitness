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
   - `AUTH_RATE_LIMIT_PER_MINUTE` (10), `AUTH_REFRESH_RATE_LIMIT_PER_MINUTE` (30), `LOGIN_MAX_FAILURES_PER_ACCOUNT` (5)
   - `TARGET_HISTORY_RATE_LIMIT_PER_MINUTE` (60, per user)
   - `NOTIFICATION_PREFS_RATE_LIMIT_PER_MINUTE` (60, per user, for each of GET and PATCH `/me/notification-preferences`)
   - `EXPORT_RATE_LIMIT_PER_HOUR` (5), `EXPORT_MAX_ROWS` (5000), `EXPORT_MAX_SESSIONS` (2000), `EXPORT_MAX_PLAN_VERSIONS` (200), `EXPORT_MAX_SETS_TOTAL` (20000), `EXPORT_MAX_PLAN_EXERCISES_TOTAL` (10000)
   - `PORT` is set by Render.
3. Build: `npm ci --include=dev && npm run build` (`prisma generate` + `tsc`).
   Start: `npm run migrate:deploy && npm start`. Migrations are idempotent, and the Free plan has no pre-deploy step.
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

## 3. Verify
- `curl https://<service>.onrender.com/health` → `{"status":"ok",...}`
- `curl 'https://<service>.onrender.com/health?db=true'` → `"db":"up"`
- API base: `https://<service>.onrender.com/api/v1/`

## 4. Android staging URL
Once the service exists, set the Android **debug** build's API base URL to `https://<service>.onrender.com/api/v1/`. No Play upload.

## Free-tier notes
- **Cold starts (accepted):** Render Free spins down after ~15 min idle, so the first request after that takes **about 50 s**. **No keep-alive pinger is used**, by choice, to stay within the free plan; the app should show a loading state and retry.
- Render Free has 512 MB RAM. argon2 (19 MiB per hash) fits.
- `backend/Dockerfile` is an alternative (runtime `docker`). Since #17, `prisma` is a runtime dependency, so the Docker image keeps the Prisma CLI and `npm run migrate:deploy` works inside it. The Blueprint uses the native Node runtime anyway, to keep the build simple.
- Node 22 is required (`engines: >=22`). argon2's native build fails on Node 20.
