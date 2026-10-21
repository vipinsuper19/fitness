# Strength progress (P1, contract)

Two read-only endpoints (no XP, no Idempotency-Key). Contract: `contracts/openapi.yaml` (`ExerciseProgressSummary`, `ExerciseProgress`, `ExerciseProgressPoint`, `PersonalRecords`).

| Call | Returns |
|---|---|
| `GET /progress/exercises?from&to&cursor&limit` | `{from, to, timezone, items: ExerciseProgressSummary[], nextCursor}`: exercises with ≥ 1 counting set in the range, `lastPerformedAt` desc then `exerciseId`; `sessions`/`lastPerformedAt` in range, `pr` all-time. |
| `GET /progress/exercises/{exerciseId}?from&to&bucket=day\|week\|month` | `{exerciseId, name, trackingType, from, to, bucket, timezone, pr, points[]}`: every period in the range. |

## Range and periods

Same as `/progress/summary` and `/nutrition/summary`: `from`, `to` required ISO dates, `from ≤ to`, ≤ 366 days, years 1900–2100, else `400 VALIDATION_FAILED`. Profile timezone (default `Asia/Kolkata`), echoed as `timezone`. `bucket` defaults to `day`; `week` is Monday–Sunday, `month` the calendar month; first/last periods clipped; `period` = first date of the clipped period. Every period is returned, including empty ones.

## What counts

- A **counting set**: `exercise_set_logs.completed = true` in a `workout_sessions` row with `status = completed`. In-progress/paused sessions don't count.
- **Discarded** sessions are deleted with their sets, so they drop out. **Corrections** (`docs/specs/session-correction.md`) edit or remove set rows, so results always reflect the corrected sets (computed live, nothing cached).
- A set's date is its session's `completedAt` as a local date (same as `xpEarned` in `/progress/summary`), not the set's `loggedAt`.
- Units are what's stored: `weightKg` (kg), `reps`. Imperial users convert on the client, as everywhere else.

## Values (`null` = no data, never 0)

- `sessions`, `sets`: counts (0 for an empty period).
- `volumeKg` = Σ `weightKg × reps` over sets with `weightKg > 0` and `reps > 0`, 2 decimals. Bodyweight (`weightKg` null or 0) and duration/distance sets add nothing, so a bodyweight-only period has `volumeKg: null`.
- `totalReps` = Σ `reps` over sets with `reps` not null (weighted **and** bodyweight). Null when no set has reps (duration exercises).
- `topSetWeightKg` = max `weightKg > 0` among sets with `reps ≥ 1`.
- **Epley 1RM** = `weightKg × (1 + reps/30)` for `weightKg > 0` and `1 ≤ reps ≤ 12`; a 1-rep set is its weight. Above 12 reps the estimate is unreliable, so those sets don't produce one. 1 decimal HALF_UP.
- Duration/distance exercises: `sessions` and `sets` count; reps/weight values are `null`. Duration and distance totals are a later item (additive fields).

## Records (decided: all-time)

`pr` is **all-time** over every counting set, independent of `from`/`to`, so the list and the detail screen show the same records and a PR never vanishes when the user narrows the range. In-range bests are already in `points` (`topSetWeightKg`, `best1RmKg`, max over the points). `maxWeightKg`, `maxReps`, `best1RmKg` each carry `…AchievedAt` = `completedAt` of the earliest session that reached the value. A correction that lowers a set lowers the record (it is recomputed, never stored). The list only includes exercises performed in the range, but their `pr` is still all-time.

## Implementation plan (SQL aggregation)

One query per call, aggregated in Postgres (no per-row loading in Node):

- Base CTE: `exercise_set_logs s JOIN workout_sessions w ON w.id = s.session_id WHERE w.user_id = $1 AND w.status = 'completed' AND s.completed` (+ `s.exercise_id = $2` for the detail).
- Local date: `(w.completed_at AT TIME ZONE $tz)::date`; range filter on it. Bucket key: `date_trunc('week'|'month', local_date)` (Postgres weeks start Monday), clipped with `GREATEST(bucket_start, $from)`; empty periods filled with `generate_series` and a LEFT JOIN.
- Per period: `COUNT(DISTINCT w.id)`, `COUNT(*)`, `SUM(weight_kg*reps) FILTER (WHERE weight_kg > 0 AND reps > 0)`, `SUM(reps)`, `MAX(weight_kg) FILTER (WHERE weight_kg > 0 AND reps >= 1)`, `MAX(CASE WHEN reps = 1 THEN weight_kg ELSE weight_kg*(1+reps/30.0) END) FILTER (WHERE weight_kg > 0 AND reps BETWEEN 1 AND 12)`. SUM/MAX over no rows are NULL, which gives the null rule for free.
- Records: same filters without the range; value via `MAX`, `achievedAt` via `DISTINCT ON (exercise_id) … ORDER BY value DESC, completed_at ASC` per metric (or `MIN(completed_at) FILTER (WHERE value = max)`).
- List: group by `exercise_id` in range for `sessions`/`lastPerformedAt`, keyset page on `(last_performed_at DESC, exercise_id DESC)` with the usual `{t, id}` cursor, then records for just that page's exercise ids.
- Rounding in SQL (`ROUND(x::numeric, 1|2)`, HALF_UP for numeric). Index check: `exercise_set_logs (session_id)`, `workout_sessions (user_id, status, completed_at)`; add the latter if missing.

## Planned tests (implementation PR, DB)

- Contract shape: required keys for both endpoints; every period present for day/week/month, Monday weeks, clipped first/last period; 366-day limit, `from > to`, bad dates, bad bucket → 400; 401; unknown exercise 404; never-performed exercise 200 with null `pr` and empty points.
- Counting: incomplete sets, in-progress/paused sessions and discarded sessions excluded; a correction that changes reps/weight or removes a set changes points and records.
- Values: volume/reps/top set/1RM on a known fixture (incl. Epley rounding, 1-rep = weight, 13-rep set excluded from 1RM but counted in reps/volume/top set); bodyweight → `volumeKg` null, `totalReps` counted; duration exercise → reps/weight null, sets counted; empty period → 0/0/nulls.
- Records all-time: a PR outside the range still shows; `achievedAt` is the earliest session on a tie.
- Timezone: a session completed at 23:50 vs 00:10 IST falls on the right local day/week (faked `completedAt`).
- List: order by `lastPerformedAt`, paging with cursor, only in-range exercises, owner-scoped.
- No writes: XP ledger unchanged; no Idempotency-Key needed.
