# Strength progress (P1, contract)

Two read-only endpoints (no XP, no Idempotency-Key). Contract: `contracts/openapi.yaml` (`ExerciseProgressSummary`, `ExerciseProgress`, `ExerciseProgressPoint`, `PersonalRecords`).

| Call | Returns |
|---|---|
| `GET /progress/exercises?from&to&cursor&limit` | `{from, to, timezone, items: ExerciseProgressSummary[], nextCursor}`: exercises with ≥ 1 counting set in the range, `lastPerformedAt` DESC then `exerciseId` DESC; `sessions`/`lastPerformedAt` in range, `pr` all-time. |
| `GET /progress/exercises/{exerciseId}?from&to&bucket=day\|week\|month` | `{exerciseId, name, trackingType, from, to, bucket, timezone, pr, points[]}`: every period in the range. |

## Range and periods

Same as `/progress/summary` and `/nutrition/summary`: `from`, `to` required ISO dates, `from ≤ to`, years 1900–2100, and **at most 366 days for every bucket** (`day`, `week` and `month`; a month bucket does not allow a longer range), else `400 VALIDATION_FAILED`. All periods use local dates in the **profile timezone**; weeks are **Monday**-based. Bucketing always uses the **current** profile timezone (nothing is stored per session), so changing the timezone re-buckets the whole history, including which local day a late-night session falls on. Profile timezone (default `Asia/Kolkata`), echoed as `timezone`. `bucket` defaults to `day`; `week` is Monday–Sunday, `month` the calendar month; first/last periods clipped; `period` = first date of the clipped period. Every period is returned, including empty ones.

## What counts

- A **counting set**: `exercise_set_logs.completed = true` in a `workout_sessions` row with `status = completed`. In-progress/paused sessions don't count.
- **Discarded sessions are excluded**: a discard deletes the session and its sets. **Corrections show up immediately** (`docs/specs/session-correction.md`): they edit or remove set rows, and every read recomputes from the current rows (nothing cached or stored). So **records can go down** after a correction or discard, e.g. fixing a mistyped 200 kg to 20 kg lowers `maxWeightKg` and its `achievedAt` moves to the next-best set.
- A set's date is its session's `completedAt` as a local date (same as `xpEarned` in `/progress/summary`), not the set's `loggedAt`.
- Units are what's stored: `weightKg` (kg), `reps`, `durationSeconds` (s), `distanceM` (m, 1 decimal). Imperial users convert on the client, as everywhere else.

## Values (`null` = no data, never 0)

- `sessions`, `sets`: counts (0 for an empty period).
- `volumeKg` = Σ `weightKg × reps` over sets with `weightKg > 0` and `reps > 0`, 2 decimals. Bodyweight (`weightKg` null or 0) and duration/distance sets add nothing, so a bodyweight-only period has `volumeKg: null`.
- `totalReps` = Σ `reps` over sets with `reps` not null (weighted **and** bodyweight). Null when no set has reps (duration exercises).
- `topSetWeightKg` = max `weightKg > 0` among sets with `reps ≥ 1`.
- **Epley 1RM** = `weightKg × (1 + reps/30)` for `weightKg > 0` and `1 ≤ reps ≤ 12`; a 1-rep set is its weight. Above 12 reps the estimate is unreliable, so those sets don't produce one. 1 decimal HALF_UP.
- **Duration and distance** are stored per set (`exercise_set_logs.duration_seconds`, `distance_m`), so points carry `totalDurationSec`, `totalDistanceM` (Σ over sets that have a value > 0) and `maxSetDurationSec`, `maxSetDistanceM` (longest single set). Each is `null` when no set in the period has that value, e.g. for reps-only exercises; reps/weight values are likewise `null` for pure duration/distance exercises.
- Values are computed from whichever fields a set has, not gated on `trackingType` (a plank logged with reps still counts its reps).

## Records (decided: all-time; confirmed by Tech Lead)

- `maxReps` is the **best reps in a single completed set**, for bodyweight and every other type that logs reps (never a sum across sets or sessions).
- `maxDurationSec` / `maxDistanceM`: longest single completed set; `null` for exercises without duration/distance.


`pr` is **all-time** over every counting set, independent of `from`/`to`, so the list and the detail screen show the same records and a PR never vanishes when the user narrows the range. In-range bests are already in `points` (`topSetWeightKg`, `best1RmKg`, max over the points). `maxWeightKg`, `maxReps`, `best1RmKg`, `maxDurationSec`, `maxDistanceM` each carry `…AchievedAt` = `completedAt` of the earliest session that reached the value. A correction that lowers a set lowers the record (it is recomputed, never stored). The list only includes exercises performed in the range, but their `pr` is still all-time.

## Implementation plan (SQL aggregation)

One query per call, aggregated in Postgres (no per-row loading in Node):

- Base CTE: `exercise_set_logs s JOIN workout_sessions w ON w.id = s.session_id WHERE w.user_id = $1 AND w.status = 'completed' AND s.completed` (+ `s.exercise_id = $2` for the detail).
- Local date: `(w.completed_at AT TIME ZONE $tz)::date`; range filter on it. Bucket key: `date_trunc('week'|'month', local_date)` (Postgres weeks start Monday), clipped with `GREATEST(bucket_start, $from)`; empty periods filled with `generate_series` and a LEFT JOIN.
- Per period: `COUNT(DISTINCT w.id)`, `COUNT(*)`, `SUM(weight_kg*reps) FILTER (WHERE weight_kg > 0 AND reps > 0)`, `SUM(reps)`, `MAX(weight_kg) FILTER (WHERE weight_kg > 0 AND reps >= 1)`, `SUM(duration_seconds) FILTER (WHERE duration_seconds > 0)`, `SUM(distance_m) FILTER (WHERE distance_m > 0)`, `MAX(duration_seconds)`, `MAX(distance_m)` (same filters), `MAX(CASE WHEN reps = 1 THEN weight_kg ELSE weight_kg*(1+reps/30.0) END) FILTER (WHERE weight_kg > 0 AND reps BETWEEN 1 AND 12)`. SUM/MAX over no rows are NULL, which gives the null rule for free.
- Records: same filters without the range; value via `MAX`, `achievedAt` via `DISTINCT ON (exercise_id) … ORDER BY value DESC, completed_at ASC` per metric (or `MIN(completed_at) FILTER (WHERE value = max)`).
- List: group by `exercise_id` in range for `sessions`/`lastPerformedAt`, ordered `lastPerformedAt DESC, exerciseId DESC`, keyset page on `(last_performed_at, exercise_id) <` the cursor with the usual `{t, id}` cursor, then records for just that page's exercise ids.
- Rounding in SQL (`ROUND(x::numeric, 1|2)`, HALF_UP for numeric). Indexes: `exercise_set_logs (session_id)` exists; `workout_sessions (user_id, status, completed_at)` is added by migration `20261021000700_sessions_user_status_completed_idx` (prefix checked unique against main and the in-flight nutrition branches).

## Planned tests (implementation PR, DB)

- Contract shape: required keys for both endpoints (incl. the duration/distance fields); every period present for day/week/month, Monday weeks, clipped first/last period.
- Malformed exercise id → 404 (same as every id path).
- Validation: 366-day limit enforced for **each** bucket (367 days with `bucket=month` → 400), `from > to`, bad dates, bad bucket → 400; 401; unknown exercise 404; never-performed exercise 200 with all-null `pr` and empty points.
- Counting: incomplete sets and in-progress/paused sessions excluded; a **discarded** session's sets disappear from points and records.
- Corrections: lowering a set's weight/reps (or removing it) on the next read changes the points and **lowers the record**, with `achievedAt` moving to the next-best set.
- Values: volume/reps/top set/1RM on a fixture (Epley rounding, 1 rep = weight, a 13-rep set excluded from 1RM but counted in reps/volume/top set); bodyweight → `volumeKg` null, `totalReps` counted; `maxReps` = best single set, not a sum (3×10 → 10).
- Duration/distance: a plank (duration) and a run (distance_duration) give `totalDurationSec`, `maxSetDurationSec`, `totalDistanceM`, `maxSetDistanceM`, `pr.maxDurationSec`/`maxDistanceM` with `achievedAt`; reps/weight fields null; reps-only exercise → duration/distance fields null; empty period → 0/0/nulls.
- Records all-time: a PR outside the range still shows; earliest session wins a tie.
- Timezone: a session completed at 23:50 vs 00:10 IST lands on the right local day and Monday week (non-default profile timezone too).
- List: order by `lastPerformedAt`, cursor paging, only in-range exercises, owner-scoped.
- Read-only: XP ledger unchanged; no Idempotency-Key needed.
