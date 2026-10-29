# GET /health/deep

## Why a separate path
`GET /health` (and `?db=true`) stays exactly as it was: Render's health check, never failing on the database. The deep check is a separate route, `/health/deep`, so it can have its own status codes and rate limit without changing `/health`'s behaviour. Like `/health` it is unprefixed (outside `/api/v1`) and public.

## Behaviour
- Runs `SELECT 1`, then reads the latest applied migration from `_prisma_migrations` (`finished_at IS NOT NULL AND rolled_back_at IS NULL`, newest `finished_at`). Each query has a ~2 s budget (`DEEP_TIMEOUT_MS`); a timeout counts as a failure.
- Body is always exactly `{ status, db, migration }`:

| Case | HTTP | Body |
|---|---|---|
| DB up, migration read | 200 | `{"status":"ok","db":"up","migration":"20261029001500_…"}` |
| DB up, migration unreadable or none | 200 | `{"status":"degraded","db":"up","migration":null}` |
| DB error or timeout | 503 | `{"status":"degraded","db":"down","migration":null}` |

- Never returned: secrets, connection strings, hostnames, Postgres/Node/app versions, error messages, stack traces. Errors are swallowed in the controller; the migration name is only returned if it matches `^[A-Za-z0-9_.-]{1,255}$`.
- The 503 body is `HealthDeep`, not the usual `Error` shape (documented exception), so monitors read the same fields in both cases.

Not written to the request log (docs/specs/request-log.md), like `/health`.

## Rate limit
Per client IP, `HEALTH_DEEP_RATE_LIMIT_PER_MINUTE` (default 12). A 429 is the standard `RATE_LIMITED` `Error` with `Retry-After`.

## Contract
`contracts/openapi.yaml` documents `/health/deep` with a path-level `servers` override (origin root, not `/api/v1`) and the `HealthDeep` schema. The drift test (`test/contract-routes.spec.ts`) treats `ROOT_PATHS` (`test/support/openapi.ts`) as contract routes, and the response checker validates their bodies.

## Tests
`backend/test/health-deep.e2e-spec.ts`: ok; DB failure (error text containing a connection string, host, version and stack) → 503 with none of it in the body; timeout → 503; migration unreadable → degraded; `/health` unchanged; rate limit 429; and, with `DB_TESTS=1`, the real latest migration with no `DATABASE_URL` substrings. `test/rate-limit-retry-after.int-spec.ts` covers the new throttle.
