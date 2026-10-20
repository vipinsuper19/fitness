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
| `CORS_ORIGINS`, `SWAGGER_ENABLED` | optional |

Secrets are set in the Render and Supabase dashboards only, never in the repo. There is no AI provider or key; AI features use rule-based fallbacks.

## Health
- `GET /health` returns `{ status: "ok", uptimeSeconds }` without touching the database, so a paused Supabase project does not restart-loop the Render service.
- `GET /health?db=true` also probes the database and reports `db: "up" | "down"`.

## Docker / Render
`docker build -t fitness-api backend/` produces a non-root Node 22 image that runs `node dist/main.js`. On Render: Docker runtime, root directory `backend`, health check path `/health`. Migrations run with `npm run migrate:deploy` against `DIRECT_URL`.
