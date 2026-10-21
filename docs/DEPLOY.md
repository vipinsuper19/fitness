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

   Preset in `render.yaml`: `NODE_VERSION=22`, `NODE_ENV=production`, `TRUST_PROXY_HOPS=2`, `SWAGGER_ENABLED=false`.

   `TRUST_PROXY_HOPS=2` is set because two proxies sit in front of the app on Render: Cloudflare's edge, then Render's own load balancer. Express has to skip both to get the real client IP, which per-IP rate limiting needs. After the first deploy, check `req.ip`. Eleven quick `POST /auth/login` calls should get a `429` on the 11th, while other networks are unaffected.

   Optional overrides; defaults come from `backend/src/config/env.ts`:
   - `JWT_ACCESS_TTL_SECONDS` (900), `JWT_REFRESH_TTL_DAYS` (30)
   - `AUTH_RATE_LIMIT_PER_MINUTE` (10), `AUTH_REFRESH_RATE_LIMIT_PER_MINUTE` (30), `LOGIN_MAX_FAILURES_PER_ACCOUNT` (5)
   - `EXPORT_RATE_LIMIT_PER_HOUR` (5), `EXPORT_MAX_ROWS` (5000), `EXPORT_MAX_SESSIONS` (2000), `EXPORT_MAX_PLAN_VERSIONS` (200), `EXPORT_MAX_SETS_TOTAL` (20000), `EXPORT_MAX_PLAN_EXERCISES_TOTAL` (10000)
   - `PORT` is set by Render.
3. Build: `npm ci --include=dev && npm run build` (`prisma generate` + `tsc`).
   Start: `npm run migrate:deploy && npm start`. Migrations are idempotent, and the Free plan has no pre-deploy step.
4. `autoDeploy` is off, so deploy by hand: **Manual Deploy → Deploy latest commit**.
5. Optional one-time seed (exercises and Indian foods; safe to re-run). Render Free has no shell, so run it locally against the same URL: `cd backend && DATABASE_URL=... DIRECT_URL=... npm run db:seed`.

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
