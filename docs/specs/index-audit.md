# Index audit: hot read paths

Audited in `backend/test/index-audit.int-spec.ts`, which runs in CI against Postgres 16 (`DB_TESTS=1`).
It seeds 1,000 users, 100 of them with a year of data:
~254k food logs, ~254k water logs, 400k XP rows, 60k target-history rows, 24k completed sessions and 144k set logs.
Rows are inserted in time order across users, as production writes them.
It then runs `VACUUM ANALYZE` and `EXPLAIN (ANALYZE, BUFFERS)` on the services' queries for one heavy user, prints every plan to the CI log, and asserts the plan shape.
The seeded rows are deleted afterwards.

| Path | Query | Index | Result |
|---|---|---|---|
| Diary | food logs for a day; water total for a day; 90-day totals by date | `food_logs_user_id_date_idx`, `water_logs_user_id_date_idx` | fine; no change |
| XP ledger | first page `ORDER BY created_at DESC, id DESC` | was `(user_id, created_at)` + sort; now `(user_id, created_at, id)` | sort removed |
| XP balance / achievements sum | `sum(amount)` per user | was a bitmap heap scan (one heap page per row, 400 for 400 rows); now an index-only scan (~10 pages) | **changed** |
| Target history | newest, resolver, list | `profile_target_history_pkey (user_id, effective_from)` | fine; no change |
| Strength points / list / records | sets JOIN completed sessions | `workout_sessions_user_id_status_completed_at_idx` or `workout_sessions_user_id_completed_at_idx` → `exercise_set_logs_session_id_idx` | fine (~1 ms for 150 sessions); no change |
| Session lookups by (user, status) | active (`in_progress`) session; completed sessions for badges, summary/streak ranges, history pages | `workout_sessions_user_id_status_completed_at_idx` | fine (0.01–0.25 ms); duplicate prefix index dropped |

## Change

Migration `20261029001400_xp_ledger_covering_index` replaces `xp_transactions_user_id_created_at_idx (user_id, created_at)` with
`xp_transactions_user_id_created_at_id_idx (user_id, created_at, id) INCLUDE (amount, source_type)`.
The old key is a prefix of the new one, so it is dropped rather than duplicated.
Prisma can't declare `INCLUDE`; it reads included columns as trailing keys, so `schema.prisma` lists all five to keep `migrate:check` clean.
The migration changes no data. It is a plain `CREATE INDEX` (Prisma migrations run in a transaction), so it blocks writes to `xp_transactions` while it builds.

## Dropped duplicate: `workout_sessions_user_id_status_idx`

Migration `20261029001500_drop_workout_sessions_user_id_status_idx` drops `workout_sessions_user_id_status_idx (user_id, status)`.
It was a strict prefix of `workout_sessions_user_id_status_completed_at_idx (user_id, status, completed_at)`, so it only cost writes and space.
`schema.prisma` no longer declares `@@index([userId, status])`.
The audit now seeds one `in_progress` session per user and asserts several things:
- the index is gone;
- every (user_id, status) lookup uses the composite index with no seq scan: the active session, completed sessions for badges, summary and streak ranges, and history pages;
- strength reads use one of the two remaining composites.

The migration is a plain `DROP INDEX IF EXISTS`. It takes a brief exclusive lock on `workout_sessions` and changes no data.

## Findings, not changed here

- **XP ledger cursor pages** (fixed in the follow-up, branch `backend/xp-ledger-row-cursor`): the cursor used to be `created_at < t OR (created_at = t AND id < i)`. Postgres can't use that as an index range, so page *n* walked and discarded every newer row (`Rows Removed by Filter`), and cost grew with page depth. `XpService.ledger` now uses the row comparison `(created_at, id) < (t, i)` in parameterised raw SQL. That is a pure index range on `xp_transactions_user_id_created_at_id_idx`, and the audit asserts it. Response shape, order and cursor format are unchanged; `test/xp-ledger-paging.int-spec.ts` pages through same-`created_at` ties at several page sizes.
- **Strength** reads visit each completed session in the range, then its set logs. That is linear in sessions, about 1 ms for 150 sessions. An `exercise_set_logs (user_id, exercise_id)` index was tried and not chosen by the planner, so it was not added.
- **90-day nutrition summary** reads one heap page per log row (~360). That takes ~2 ms and was left as is.
