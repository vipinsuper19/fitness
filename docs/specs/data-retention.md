# Data retention: `purge:expired`

Script: `backend/src/maintenance/purge-expired.ts` (`npm run purge:expired`). Workflow: `.github/workflows/purge-expired.yml` (manual only, no schedule). Runbook: [runbooks/data-retention.md](../runbooks/data-retention.md).

**A real run (`--execute` / `execute: true`) against production requires Vipin's explicit OK, in his own words.** Dry run first.

## What is purged

| Table | Eligible rows | Window | Source of the window |
|---|---|---|---|
| `idempotency_keys` | `expires_at <= now` | fixed: the 24 h TTL (not configurable here) | `IDEMPOTENCY_TTL_MS = 24 h` in `src/idempotency/idempotency.service.ts`; `expires_at = created_at + 24 h`; schema comment "Rows expire after 24 hours"; `idempotent-write.service.ts` "store … for 24 h". Expired rows are already treated as absent by the service, so deleting them changes no behaviour. |
| `refresh_tokens` | revoked or expired more than *margin* ago, **only in families with no live token** (keeps reuse detection) | `PURGE_REFRESH_TOKEN_MARGIN_DAYS`, default **7**, min **1** | PR #139 |
| `notification_sends` | `status IN ('sent','failed')` **and** `created_at < now − window` | `PURGE_NOTIFICATION_SENDS_DAYS`, default **90**, min **30** | This spec. The minimum of 30 covers the 30-day `notificationSends` window in `GET /me/export` ([notification-sending.md](notification-sending.md) §export). |

- `pending`, `held` and `sending` rows are **never** purged, at any age. Other end states (`dropped_quiet`, `capped`, `suppressed`, `coalesced`, `expired`, `skipped_stale`) are out of scope of this script for now.
- Boundaries: an idempotency key expiring exactly at `now` goes; a `notification_sends` row created exactly at the cutoff stays (strictly older only).
- `notification_sends` exists only once push sending is built. Until then the script prints `notification_sends: table not present, skipped` and deletes nothing.
- Note: [notification-sending.md](notification-sending.md) §6 also plans an in-app 30-day purge by the tick. Whichever runs, the tick must follow the same rule (final states only); the shorter of the two windows wins in practice.

## Safety

- **Dry run by default.** Only `--execute` deletes. Any other argument is rejected.
- Windows are read from env by the CLI; unset or blank = default. A value below the minimum, or not a whole number of days, **fails the run** (never clamped).
- Deletes run in batches of 1000 (one transaction per batch) and are idempotent.
- Output: per-table counts and cutoffs only. Never ids, user ids, tokens, hashes or the database URL (the workflow also redacts any `postgres://` string).

## Tests

`test/purge-expired.int-spec.ts` (DB): dry run deletes nothing; execute deletes exactly the eligible rows at the boundaries (1 ms past vs exactly at the cutoff), in batches, idempotently; `pending`/`sending`/`held` rows untouched at 400 days; a custom window; missing table skipped. `test/purge-windows.spec.ts`: env defaults, minimums, garbage rejected.
