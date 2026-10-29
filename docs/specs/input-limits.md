# Input limits (abuse caps)

Every request body, string, array, number and page size the API accepts has an upper bound. The server
enforces each one (class-validator DTOs plus the JSON body parser), and `contracts/openapi.yaml` mirrors it,
so the contract and examples tests agree with the server. Tests: `backend/test/input-limits.spec.ts`
and `backend/test/hardening.e2e-spec.ts`.

## Body size

| Route | Cap | Over the cap |
|---|---|---|
| `/api/v1/workout-plans/**` (plan create, PATCH, rename, …) | 256 KB | `413 PAYLOAD_TOO_LARGE` |
| Everything else | 100 KB (102400 bytes) | `413 PAYLOAD_TOO_LARGE` |

The 413 uses the standard `Error` shape (`code`, `message`, `requestId`, with the `X-Request-Id` header).
It is returned before authentication and validation. Plans get a larger cap because a maximal plan
(7 workouts × 30 exercises, 500-character notes, 1000-character rationale) is about 145 KB of JSON.
A maximal plan without notes is about 40 KB.

## Field caps

Over-max values are `400 VALIDATION_FAILED`, with the offending path in `details.fields`.

| Field | Cap |
|---|---|
| Email / password / displayName | 254 / 128 / 60 chars |
| Refresh token | 512 chars |
| Plan name, workout name, rename | 80 chars |
| Plan rationale / exercise notes | 1000 / 500 chars |
| Workouts per plan / exercises per workout | 7 / 30 |
| Sets per planned exercise / exercise `order` | 20 / 1000 (**new**) |
| Plan `baseVersion`, `?version`, session `planVersion` | 1,000,000 (`baseVersion` **new**) |
| Generate `equipment` | 10 items (one per Equipment value) |
| Session notes (start, update, complete, correction) | 1000 chars |
| Set `setIndex` / reps / weightKg / durationSeconds / distanceM | 50 / 1000 / 1000 / 86400 / 1,000,000 |
| Food name / brand / search query / exercise query | 120 / 80 / 100 / 100 chars |
| Nutrients input (create, update, override) | 5000 kcal, 900 g per macro, 9000 mg sodium |
| Servings / servingSize / gramsPerServing / water | 100 / 10000 / 10000 / 5000 ml |
| Goal start/target value | 999,999 |
| Challenge `templateCode` / `target` | 40 chars / 1000 (**new**) |
| Pagination `cursor` / `limit` | 200 chars / 1–100 (default 20) |

Two inputs have no explicit cap and are bounded only by the body size:

- `WorkoutPlanUpdate.name` is deprecated and ignored, and is documented as never validated.
- Notification-preference keys outside the known set are rejected by name.

## Lists

Every paged list (`/exercises`, `/foods/search`, `/workout-plans`, `/workout-sessions/history`,
`/body-measurements`, `/progress/exercises`, `/xp/ledger`, `/challenges`) caps `limit` at 100 (`400` above
that). Some lists are not paged:

- Goals, food and water logs for one date, badges, challenge templates and target history.
- These return everything for the user, or for the day.
- They are left unchanged for compatibility: adding paging would change the response shape.

## Behaviour changes in this release

1. Plan routes now accept JSON bodies up to 256 KB. Before, they were capped at 100 KB.
2. Plan `baseVersion` above 1,000,000 is now `400`.
3. Challenge `target` above 1000 is now `400`. Before, it was rejected as not one of the template's `targets`, which is a `400` too, only with a different message.
4. In the contract only, with no server change: `PAYLOAD_TOO_LARGE` and the `413` response are documented on every operation with a body. The contract now also lists the `maximum`/`maxItems` the server already enforced: `NutrientsInput`, goal values, `order`, `equipment`, `planVersion` and `?version`.
