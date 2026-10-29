# Account data audit: export and deletion

Every user-owned table must be (a) in `GET /me/export` (validated against `DataExport`) or deliberately excluded, and
(b) removed by `DELETE /me`. `test/account-data-audit.int-spec.ts` enforces this:

- **Table list.** The test derives it from Postgres: every table reachable from `users` by foreign keys, plus any table
  with a `user_id`/`owner_id` column. It must equal the test's `COVERAGE` map. Every other table must be listed in
  `NOT_USER_OWNED`. A new user-owned table therefore fails CI until it is classified here and in the test.
- **Seeding.** One row or more in every table, for two users. It writes through the API, except a session correction
  row, and a badge only if none was earned.
- **Export.** Each exported section must be present and non-empty, and the response checker validates the whole body
  against the contract. No secret keys may appear.
- **Deletion.** After `DELETE /me`, the deleted user has zero rows in every table. The other user's counts are unchanged.

| Table | Export section | Deletion |
|---|---|---|
| users | `user` (no password hash) | explicit `user.delete` |
| user_profiles | `profile` | FK cascade |
| profile_target_history | `targetHistory` | FK cascade |
| notification_preferences | `notificationPreferences` | FK cascade |
| fitness_goals | `goals` | FK cascade |
| workout_plans, workout_plan_versions, planned_workouts, planned_exercises | `workoutPlans` (one entry per version) | FK cascade (chain from workout_plans) |
| workout_sessions | `workoutSessions` | FK cascade |
| exercise_set_logs | `workoutSessions[].sets` | FK cascade (user and session) |
| session_corrections | `sessionCorrections` (**added by this audit**) | FK cascade |
| food_items (custom, `owner_id`) | `customFoods` (soft-deleted ones included) | FK cascade; catalog rows have no owner |
| food_logs | `foodLogs` | FK cascade |
| water_logs | `waterLogs` | FK cascade |
| body_measurements | `bodyMeasurements` | FK cascade |
| xp_transactions | `xpLedger` | FK cascade |
| user_badges | `badges` | FK cascade |
| user_challenges | `challenges` | FK cascade |
| refresh_tokens | **excluded** | revoked explicitly, then FK cascade |
| idempotency_keys | **excluded** | FK cascade (sign-up rows get `user_id` once the account exists) |

**Strength and macros** have no tables of their own. Strength progress is derived from set logs. Macro targets live in
`user_profiles` and `profile_target_history`, and macro totals in `food_logs`.

**Not user-owned.** `exercises` is the seeded catalog and `_prisma_migrations` is bookkeeping.

## Exclusions

- **refresh_tokens.** It holds only token hashes and session metadata. It has no user content, and exporting the
  hashes would be a credential leak.
- **idempotency_keys.** These are request internals: a body hash, a stored replay response and status. The rows expire
  after 24 hours, and the replay bodies duplicate data that is already exported. They can never carry tokens (a DB
  CHECK prevents it).
- **session_corrections.idempotency_key.** Not exported, for the same reason.
- **Planned tables.** `notificationDevices` and `notificationSends` are documented in the contract, but their tables
  don't exist yet. When they are added, the audit test will require them to be classified.

## Gap fixed

`session_corrections` was not exported: the kind, the session and the XP delta of each correction or discard. Only its
ledger row (`xpLedger`, `session_correction`) was. The export now has an optional `sessionCorrections` array
(`ExportedSessionCorrection`: `id`, `sessionId`, `kind`, `xpDelta`, `createdAt`). Each `id` is the `sourceId` of its
ledger row. The array is optional, so the change is backward compatible. No migration was needed: every table already
cascades from `users`.
