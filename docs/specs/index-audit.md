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
| Strength points / list / records | sets JOIN completed sessions | `workout_sessions_user_id_status_idx` → `exercise_set_logs_session_id_idx` | fine (~1 ms for 150 sessions); no change |

## Change

Migration `20261029001400_xp_ledger_covering_index` replaces `xp_transactions_user_id_created_at_idx (user_id, created_at)` with
`xp_transactions_user_id_created_at_id_idx (user_id, created_at, id) INCLUDE (amount, source_type)`.
The old key is a prefix of the new one, so it is dropped rather than duplicated.
Prisma can't declare `INCLUDE`; it reads included columns as trailing keys, so `schema.prisma` lists all five to keep `migrate:check` clean.
The migration changes no data. It is a plain `CREATE INDEX` (Prisma migrations run in a transaction), so it blocks writes to `xp_transactions` while it builds.

## Findings, not changed here

- **XP ledger cursor pages**: the service's cursor is `created_at < t OR (created_at = t AND id < i)`. Postgres can't use that as an index range, so page *n* walks and discards every newer row (the plan shows `Rows Removed by Filter`). Cost grows with page depth. A row comparison `(created_at, id) < (t, i)` is an index range (the test prints that plan too). This would need raw SQL in `XpService.ledger`; it is a follow-up.
- **Strength** reads visit each completed session in the range, then its set logs. That is linear in sessions, about 1 ms for 150 sessions. An `exercise_set_logs (user_id, exercise_id)` index was tried and not chosen by the planner, so it was not added.
- **`workout_sessions_user_id_status_idx`** is a prefix of `workout_sessions_user_id_status_completed_at_idx`. It is redundant, but it was left alone: this PR only adds what helps.
- **90-day nutrition summary** reads one heap page per log row (~360). That takes ~2 ms and was left as is.
