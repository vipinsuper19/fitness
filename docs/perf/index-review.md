# Index review (hot paths)

One pass over the indexes behind the app's hot paths, measured on seeded data. Migration:
`backend/prisma/migrations/20261029001600_index_review`. Tests: `backend/test/index-review.int-spec.ts`
(DB, prints every before/after plan to the CI log) and `backend/test/index-review.spec.ts` (static).
Builds on the earlier [index audit](../specs/index-audit.md) (diary, XP ledger, target history, strength).

## Method

- **Data** (`backend/test/support/index-review-sql.ts`, local/CI Postgres only, never prod): 1 main user with a
  year of everything (3 plan versions, 180 completed sessions × 15 sets, 4 food + 4 water entries a day, 12 target
  rows, ~400 XP rows, 24 badges, 52 weekly challenges, 300 idempotency keys) plus 1000 other users with a month
  (15 sessions, 30 days of diary, ~25 XP rows, 4 challenges, 20 idempotency keys). Totals: 15,180 sessions,
  227,700 set logs, 121,460 food logs, 25,400 XP rows, 20,300 idempotency keys. Rows are inserted in time order
  across users, as production writes them.
- `VACUUM ANALYZE`, one warm-up run, then `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)` of the services' queries (same
  predicates and order as the Prisma / raw SQL) for the main user. "Before" is the index set on `main` (662a8d0);
  "after" is with this migration. Numbers below are from Postgres 17.5 on the dev box (warm cache, best of 5);
  the CI job prints the same plans and a before → after timing summary (`--- index-review:` lines).
- DELETE /me is explained for another user inside a rolled-back transaction; its FK triggers are shown as
  separate `fk:` queries (the lookups the RI triggers run per deleted parent row).

## Changes (one migration)

| Change | Index | Why |
|---|---|---|
| **add** | `workout_sessions_plan_version_id_idx` (`plan_version_id`) | FK `ON DELETE SET NULL`: deleting a plan version (DELETE /me cascades through plans) seq-scanned `workout_sessions` once per version. |
| **add** | `workout_sessions_planned_workout_id_idx` (`planned_workout_id`) | Same, once per planned workout (3+ per version). |
| **add** | `idempotency_keys_user_id_idx` (`user_id`) | FK `ON DELETE CASCADE` from users seq-scanned `idempotency_keys` (the largest table without a user index). |
| **drop** | `workout_plans_user_id_idx` (`user_id`) | Prefix of `workout_plans_user_id_archived_at_idx`. |
| **drop** | `workout_sessions_user_id_completed_at_idx` (`user_id, completed_at`) | Every service query on `completed_at` also filters `status = 'completed'`; they now use `workout_sessions_user_id_status_completed_at_idx` with the same timings (table below). One fewer index to write on every session insert/update. |

Considered and **not** added: `exercise_set_logs (user_id, completed, session_id)` for the badge
completed-set count. It turned the set side into an index-only scan (363 → 283 buffers) but saved ~0.2 ms of
4.3 ms, because the cost is the join to sessions (below), not the set lookup. Not worth a fourth index on the
most-written table. Everything else is already index-backed (table below).

## Biggest wins

| | Before | After |
|---|---:|---:|
| DELETE /me (a user with a month of data) | 10.7 ms | 5.3 ms |
| FK check: sessions by planned workout (×3 per version) | 1.03 ms, Seq Scan, 281 buffers | 0.09 ms, index, 12 buffers |
| FK check: sessions by plan version | 1.11 ms, Seq Scan, 291 buffers | 0.14 ms, index, 32 buffers |
| FK check: idempotency keys by user | 1.70 ms, Seq Scan, 493 buffers | 0.17 ms, index, 42 buffers |

These scale with the whole table, not the user: at 100× the rows each seq scan is ~100× slower, and DELETE /me
runs one per plan version and planned workout. The read paths were already index-backed; the drops cost them
nothing.

## All paths (before → after)

| Path | Before ms | After ms | Buffers before → after | After plan uses |
|---|---:|---:|---:|---|
| today: profile timezone | 0.060 | 0.110 | 3 → 3 | `user_profiles_pkey` |
| today: sessions completed today | 0.029 | 0.034 | 2 → 2 | `workout_sessions_user_id_status_completed_at_idx` |
| today: streak (completed since 60 days) | 0.092 | 0.090 | 18 → 18 | `workout_sessions_user_id_status_completed_at_idx` |
| today: session XP awards | 0.220 | 0.839 | 38 → 38 | `xp_transactions_user_id_source_type_source_id_key`, `workout_sessions_user_id_status_completed_at_idx` |
| today: food totals | 0.098 | 0.148 | 7 → 7 | `food_logs_user_id_date_idx` |
| today: water total | 0.092 | 0.129 | 7 → 7 | `water_logs_user_id_date_idx` |
| sessions: history page | 0.106 | 0.165 | 27 → 27 | `workout_sessions_user_id_status_completed_at_idx` |
| sessions: sets for a history page | 0.655 | 0.828 | 121 → 121 | `workout_sessions_user_id_status_completed_at_idx`, `exercise_set_logs_session_id_idx` |
| sessions: detail row | 0.044 | 0.069 | 6 → 6 | `workout_sessions_pkey`, `workout_sessions_user_id_status_completed_at_idx` |
| sessions: detail sets | 0.115 | 0.116 | 17 → 17 | `workout_sessions_user_id_status_completed_at_idx`, `exercise_set_logs_session_id_idx` |
| sessions: completed-set count on complete | 0.127 | 0.104 | 8 → 8 | `workout_sessions_user_id_status_completed_at_idx`, `exercise_set_logs_session_id_idx` |
| xp: ledger first page | 0.129 | 0.145 | 23 → 23 | `xp_transactions_user_id_created_at_id_idx` |
| xp: ledger next page | 0.178 | 0.149 | 15 → 15 | `xp_transactions_user_id_created_at_id_idx` |
| xp: balance | 0.179 | 0.175 | 20 → 20 | `xp_transactions_user_id_created_at_id_idx` |
| nutrition: food logs for a day | 0.081 | 0.090 | 10 → 10 | `food_logs_user_id_date_idx` |
| nutrition: water for a day | 0.075 | 0.072 | 4 → 4 | `water_logs_user_id_date_idx` |
| nutrition: targets in effect | 0.043 | 0.040 | 3 → 3 | `profile_target_history_pkey` |
| progression: plan | 0.045 | 0.042 | 3 → 3 | `workout_plans_user_id_archived_at_idx` |
| progression: versions | 0.042 | 0.040 | 3 → 3 | `workout_plan_versions_plan_id_version_key` |
| progression: planned workouts | 0.089 | 0.096 | 21 → 21 | `workout_plan_versions_plan_id_version_key`, `planned_workouts_plan_version_id_idx` |
| progression: planned exercises | 0.211 | 0.175 | 84 → 84 | `workout_plan_versions_plan_id_version_key`, `planned_workouts_plan_version_id_idx`, `planned_exercises_planned_workout_id_idx` |
| progression: history for one exercise | 1.542 | 1.243 | 780 → 792 | `workout_plan_versions_plan_id_version_key`, `workout_sessions_plan_version_id_idx`, `workout_sessions_user_id_status_completed_at_idx`, `exercise_set_logs_session_id_idx` |
| badges: list | 0.067 | 0.066 | 7 → 7 | `user_badges_user_id_badge_code_key` |
| badges: completed-set count | 5.654 | 4.369 | 363 → 363 | `exercise_set_logs_user_id_client_set_id_key`, `Seq Scan workout_sessions` |
| challenges: list page | 0.059 | 0.059 | 6 → 6 | `user_challenges_user_id_joined_at_id_idx` |
| challenges: active | 0.063 | 0.060 | 9 → 9 | `user_challenges_one_active_per_template` |
| challenges: weekly completed count | 0.060 | 0.071 | 5 → 5 | `user_challenges_user_id_status_joined_at_id_idx` |
| challenges: already joined this week | 0.064 | 0.064 | 6 → 6 | `user_challenges_user_id_status_joined_at_id_idx` |
| fk: sessions by plan version (SET NULL) | 1.107 | 0.135 | 291 → 32 | `workout_sessions_plan_version_id_idx` |
| fk: sessions by planned workout (SET NULL) | 1.028 | 0.094 | 281 → 12 | `workout_sessions_planned_workout_id_idx` |
| fk: idempotency keys by user (CASCADE) | 1.695 | 0.167 | 493 → 42 | `idempotency_keys_user_id_idx` |
| DELETE /me (other user) | 10.703 | 5.329 | 40 → 40 | `users_pkey` |

Sub-0.2 ms differences are noise. "Seq Scan" appears after only in the badge count and is explained next.

## Finding: badge completed-set count (no change needed)

`AchievementsService` counts the user's completed sets in completed sessions:
`exerciseSetLog.count({ where: { userId, completed: true, session: { status: 'completed' } } })`. An earlier
draft of this review guessed that Prisma renders the relation filter as a whole-table `IN (subquery)`. Prisma
query logging (client 7.10.1, against the seeded local DB) shows it doesn't. The generated SQL is a per-user join:

```sql
SELECT COUNT(*) AS "_count$_all" FROM (SELECT "public"."exercise_set_logs"."id" FROM "public"."exercise_set_logs"
  LEFT JOIN "public"."workout_sessions" AS "j0" ON ("j0"."id") = ("public"."exercise_set_logs"."session_id")
  WHERE ("public"."exercise_set_logs"."user_id" = $1 AND "public"."exercise_set_logs"."completed" = $2
    AND ("j0"."status" = CAST($3::text AS "public"."SessionStatus") AND ("j0"."id" IS NOT NULL))) OFFSET $4) AS "sub"
-- params: [userId, true, "completed", "0"]
```

`EXPLAIN (ANALYZE, BUFFERS)` of that statement, prepared, with both the custom and the forced generic plan:

- The user's sets come from an index (`exercise_set_logs_user_id_client_set_id_key`): **2,700 rows**, 87 buffers. No other user's sets are read.
- A **hash join** to `workout_sessions` (a Seq Scan of 15,180 rows, 276 buffers; 363 buffers in total), about **5–6 ms**. A user with a month of data (225 sets) gets the same plan in 5.3 ms.
- Forcing a nested loop (a primary-key lookup per set instead of the hash) is **worse: 627 vs 363 buffers**. At this size the planner's hash join is the right choice, and it will switch by itself as the table grows.

**Conclusion:** no rewrite and no index needed. The extra `(user_id, completed, session_id)` index (see Changes)
stays rejected.

## Key plans

FK check fired by DELETE /me, before (Seq Scan on the whole table):

```
LockRows (actual time=0.041..1.080 rows=15 loops=1)
  ->  Seq Scan on workout_sessions x (actual time=0.025..1.053 rows=15 loops=1)
        Filter: (plan_version_id = '…-8200-000000000004'::uuid)
        Rows Removed by Filter: 15165
        Buffers: shared hit=276
Execution Time: 1.107 ms
```

After:

```
LockRows (actual time=0.046..0.108 rows=15 loops=1)
  ->  Bitmap Heap Scan on workout_sessions x (actual time=0.022..0.071 rows=15 loops=1)
        ->  Bitmap Index Scan on workout_sessions_plan_version_id_idx (rows=15)
              Buffers: shared hit=2
Execution Time: 0.135 ms
```

`idempotency_keys` by user: Seq Scan, 20,280 rows removed, 1.695 ms → Bitmap Index Scan on
`idempotency_keys_user_id_idx`, 0.167 ms.

DELETE /me trigger times (another user), before → after:

```
Trigger for constraint idempotency_keys_user_id_fkey on users:                        1.954 → 0.219 ms
Trigger for constraint workout_sessions_plan_version_id_fkey on workout_plan_versions: 0.910 → 0.058 ms
Trigger for constraint workout_sessions_planned_workout_id_fkey on planned_workouts:   2.341 → 0.083 ms (3 calls)
Execution Time:                                                                       10.703 → 5.329 ms
```

The full before/after plans for every path are printed by `index-review.int-spec.ts` in the backend CI job.

## Reproduce locally

Any scratch Postgres 17 (never prod): apply `backend/prisma/migrations/*/migration.sql` in order, run the
`SEED` statements from `test/support/index-review-sql.ts`, `VACUUM ANALYZE`, then EXPLAIN the `QUERIES`. For
"before", run `TO_BEFORE` inside a transaction and roll it back. Or: `DB_TESTS=1 DATABASE_URL=… npx jest test/index-review.int-spec.ts`.
