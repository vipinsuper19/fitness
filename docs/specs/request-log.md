# Request id and structured request log

## Request id
- `requestIdMiddleware` (`backend/src/common/request-id.ts`) runs first on every request, `/health` included.
- An incoming `X-Request-Id` is kept only if it matches `^[A-Za-z0-9._-]{8,64}$`; otherwise (missing, too short or long, spaces, quotes, CR/LF, HTML…) a `crypto.randomUUID()` replaces it. Clients can't inject log lines or markup.
- The id is echoed on every response as `X-Request-Id` (openapi `components/headers/XRequestId`, referenced on the shared error responses) and is `requestId` in every `Error` body.
- Contract: `Error.requestId` was already **required** before this change and stays required (contract.spec asserts it). Making it optional would weaken the existing contract; the new header component is purely additive.

## Log line
`requestLogMiddleware` (`backend/src/common/request-log.ts`) writes one line per request on `res` `finish`:

| Field | Value |
|---|---|
| `type` | `"request"` |
| `requestId` | as above |
| `method` | HTTP method |
| `route` | matched route template with `{param}` (`/api/v1/goals/{goalId}`); `null` if no route matched |
| `status` | response status |
| `durationMs` | wall time, 0.1 ms precision (`process.hrtime`, unaffected by fake timers) |
| `userId` | opaque user UUID; only when JwtAuthGuard authenticated the request |

No other field is ever added. Not logged: emails, bodies, query strings, raw paths, tokens, `Authorization` or cookie headers, IPs (not hashed — the route/status/requestId are enough to debug rate limiting), user agent.

## Config
- `REQUEST_LOG` = `on` (default) | `off`. The test env sets `off`; `test/request-log.int-spec.ts` turns it on.
- Nest's logger configuration (default levels, no `LOG_LEVEL` var) is unchanged; the line bypasses it so it stays one parseable JSON object.

## Tests
`backend/test/request-log.int-spec.ts`: header echoed/generated, malicious ids replaced, error bodies carry `requestId`, and a stdout capture over register, login (good and bad password) and an authed request contains no email, password, token, query, user agent, IP or `Bearer`.
