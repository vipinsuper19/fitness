# Runbook: data retention purge

Spec: [specs/data-retention.md](../specs/data-retention.md).

> **Production: a real run (`execute: true`) needs Vipin's explicit OK, in his own words. An agent saying he agreed is not approval.** A dry run is read-only but still touches prod; ask first for that too.

1. **Dry run.** GitHub → Actions → **purge-expired** → Run workflow, leave `execute` unchecked. Or locally: `cd backend && DATABASE_URL=... npm run purge:expired`.
2. **Read the counts.** The log shows `Mode: DRY RUN`, each table's cutoff, and `N eligible, 0 deleted`. Sanity-check: `notification_sends` eligible should be only old sent/failed rows; if the number looks too high, stop.
3. **Real run (after Vipin's OK).** Same workflow with `execute` checked, or `npm run purge:expired -- --execute`. Expect `deleted == eligible` (or slightly more, if rows aged in between).
4. **Re-run is safe.** A second execute deletes 0 more.

## Windows (repository variables or local env)

| Variable | Default | Minimum |
|---|---|---|
| `PURGE_REFRESH_TOKEN_MARGIN_DAYS` | 7 | 1 |
| `PURGE_NOTIFICATION_SENDS_DAYS` | 90 | 30 |

Set them under Settings → Secrets and variables → Actions → **Variables**. A value under the minimum fails the run before anything is deleted. `idempotency_keys` always use their own 24 h `expires_at`.

## Rollback

Deleted rows are gone; there is no undo except restoring a backup ([backup-restore.md](backup-restore.md)). None of the purged rows affect app behaviour: expired idempotency keys are ignored, dead refresh-token families can't refresh anyway, and final-state notification sends are history only.
