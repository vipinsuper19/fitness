# Runbook: Supabase backup and restore

Scope: the fitness API database (Supabase Postgres, **Free plan** per [`render.yaml`](../../render.yaml) and
[DEPLOY.md](../DEPLOY.md)). All app data lives in the `public` schema and is owned by Prisma
(`backend/prisma/migrations`, plus the `_prisma_migrations` history table). We don't use Supabase Auth, Storage,
Realtime or Edge Functions.

Never paste a connection string or password into a command line, a doc, a ticket or a chat. Every command
below reads it from an environment variable that you set in your own shell. Use `read -rs`, so the value stays
out of shell history.

## 1. What Supabase gives us (depends on the plan)

From [Supabase: Database Backups](https://supabase.com/docs/guides/platform/backups) (checked 2026-10-29):

| Plan | Automatic daily backups | Retention | Point-in-Time Recovery (PITR) |
|---|---|---|---|
| **Free (ours)** | **None.** Supabase recommends that Free projects "regularly export their data using the Supabase CLI `db dump` command and maintain off-site backups". | — | Not available |
| Pro | Yes, in Dashboard → Database → Backups | last 7 days | Paid add-on (needs at least the Small compute add-on) |
| Team | Yes | last 14 days | Paid add-on |
| Enterprise | Yes | up to 30 days | Paid add-on |

What else depends on the plan, or holds on every plan:

- **On Free, our own dumps are the only backups.** No dashboard restore, PITR or Management API backups endpoint helps us.
- Dashboard restores (Pro and up) restore **in place**. The project is inaccessible while it runs, and everything written after the backup is lost. PITR's worst-case RPO is about 2 minutes. When PITR is on, daily backups stop. ([source](https://supabase.com/docs/guides/platform/backups))
- Daily backups don't store passwords for custom roles. We have none: the app connects as `postgres` through the session pooler.
- **Deleting a project permanently deletes its backups.** Never delete the prod project to "start clean".
- If we move to Pro, add the daily backups or PITR to section 5 as the first restore option, and keep the manual dump before risky migrations anyway. A daily backup can be up to 24 h old.

## 2. Manual dump before risky migrations

**When:** before any deploy whose migrations drop or rename a column or table, rewrite data (`UPDATE`/`DELETE`
in `migration.sql`), add a constraint that may fail on existing rows, or run a backfill. Before a Free-plan
deploy that only adds a table or an index, a dump is optional but cheap.

**Tooling:** `pg_dump` / `pg_restore` with a **major version ≥ the server's** (check with
`SELECT version();`). Alternatively the Supabase CLI. `supabase db dump` needs Docker; see Supabase's
[Backup and Restore using the CLI](https://supabase.com/docs/guides/platform/migrating-within-supabase/backup-restore).

**Connection:** use the **session pooler** URI (port 5432), as DEPLOY.md does. The direct host is IPv6-only.
Take it from Supabase → Project → **Connect** → Session pooler.

```bash
# 1. Load the URL without echoing it or writing it to history.
read -rs PROD_DB_URL && export PROD_DB_URL     # paste the session-pooler URI, press Enter
export STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p ~/fitness-backups && chmod 700 ~/fitness-backups && cd ~/fitness-backups

# 2. Note the latest applied migration (it goes in the file name and the deploy notes).
psql "$PROD_DB_URL" -Atc 'SELECT migration_name FROM _prisma_migrations WHERE finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1'

# 3. Dump. Custom format (compressed, selective restore), the public schema only (app tables plus _prisma_migrations),
#    without owners or grants, so it restores cleanly into any Postgres or Supabase project.
pg_dump "$PROD_DB_URL" --format=custom --schema=public --no-owner --no-privileges \
  --file "fitness-$STAMP.dump"

# 4. Check that it is readable and complete before you go on.
pg_restore --list "fitness-$STAMP.dump" | grep -c 'TABLE DATA'      # expect 23: 22 app tables + _prisma_migrations
pg_restore --list "fitness-$STAMP.dump" | grep '_prisma_migrations'
sha256sum "fitness-$STAMP.dump" > "fitness-$STAMP.dump.sha256"

unset PROD_DB_URL
```

Equivalent with the Supabase CLI (plain SQL, per Supabase's docs). This variant includes all non-system schemas:

```bash
supabase db dump --db-url "$PROD_DB_URL" -f "schema-$STAMP.sql"
supabase db dump --db-url "$PROD_DB_URL" -f "data-$STAMP.sql" --use-copy --data-only
```

**Storing the dump.** It contains user data, including emails and password hashes:

- Keep it encrypted and off-site (for example `gpg --symmetric`, or an encrypted drive). Never commit it to the repo or attach it to an issue or PR.
- Keep the last few pre-migration dumps and at least one weekly one. Delete older ones; they hold personal data.
- On Free, also take a **weekly** dump with the same command, since nothing else backs us up.

## 3. Restore to a scratch project first, always

Never restore straight into prod. First prove the dump on a scratch database, then decide (section 5).

**Scratch target**, either of:
- a new **Supabase project** (Free allows two active projects), with the same region and Postgres major version as prod; or
- a local throwaway Postgres of the same major version: `docker run --rm -e POSTGRES_PASSWORD=scratch -p 54329:5432 postgres:17`.

```bash
read -rs SCRATCH_DB_URL && export SCRATCH_DB_URL   # the scratch session-pooler URI, or postgresql://postgres:scratch@localhost:54329/postgres

# Safety check: refuse to continue if this is the prod project ref.
case "$SCRATCH_DB_URL" in *"$PROD_PROJECT_REF"*) echo "This is PROD. Stop." ; unset SCRATCH_DB_URL ;; esac

sha256sum -c "fitness-$STAMP.dump.sha256"
pg_restore --dbname "$SCRATCH_DB_URL" --no-owner --no-privileges --clean --if-exists \
  --single-transaction --exit-on-error "fitness-$STAMP.dump"
```

(`PROD_PROJECT_REF` is the non-secret project ref from the Supabase dashboard URL.) A new Supabase project
already has a `public` schema, and `--clean --if-exists` handles that.

**Verify on scratch:**

```bash
psql "$SCRATCH_DB_URL" -Atc 'SELECT count(*) FROM _prisma_migrations WHERE finished_at IS NOT NULL'
psql "$SCRATCH_DB_URL" -Atc 'SELECT (SELECT count(*) FROM users) users, (SELECT count(*) FROM workout_sessions) sessions, (SELECT count(*) FROM food_logs) food, (SELECT count(*) FROM xp_transactions) xp'
cd backend && DATABASE_URL="$SCRATCH_DB_URL" DIRECT_URL="$SCRATCH_DB_URL" npx prisma migrate status   # "Database schema is up to date" at the dump's migration
```

Optionally run the API against scratch (`DATABASE_URL=$SCRATCH_DB_URL npm run start:dev`) and check
`GET /health/deep`, which reports `migration`, plus a login with a test account.

When you're done, `unset SCRATCH_DB_URL`. Pause or delete the scratch project; it holds a copy of user data.

## 4. Restore into prod (only after section 3 passed and the decision in section 5)

1. Announce downtime. In Render, **suspend** the API service (Dashboard → service → Settings → Suspend), so no writes land mid-restore.
2. Take a fresh dump of the current (broken) prod state with section 2. That way the restore is itself reversible, and data written since the good dump can be recovered by hand.
3. Restore with the same `pg_restore` command, using `--dbname "$PROD_DB_URL"`. `--single-transaction` means a failure leaves prod untouched.
4. Verify with the section 3 queries against prod.
5. Make sure the code you resume matches the restored `_prisma_migrations` (see the DEPLOY.md rollback checklist). If prod was restored to *before* migration N, the deployed commit must not include N, or the next start re-applies it.
6. Resume the service. Check `GET /health` and `GET /health/deep` (it should show the expected migration), then watch the logs.

Data written between the dump and the restore is lost unless you replay it from the step 2 dump. Record the window.

## 5. Restore or forward-fix?

Prefer a **forward fix** (a new migration, see DEPLOY.md → Migration rollback checklist) whenever the data is still
there. Restore only when data has been **destroyed or corrupted** (dropped column or table, bad `UPDATE`/`DELETE`)
and can't be rebuilt from what remains. Restore accepts losing the writes since the dump, so weigh that window
against the damage.

## References

- Supabase, [Database Backups](https://supabase.com/docs/guides/platform/backups): plans, retention, PITR, restore downtime.
- Supabase, [Backup and Restore using the CLI](https://supabase.com/docs/guides/platform/migrating-within-supabase/backup-restore): `supabase db dump`, `psql` restore.
- PostgreSQL, [pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html) and [pg_restore](https://www.postgresql.org/docs/current/app-pgrestore.html).
- [DEPLOY.md → Migration rollback checklist](../DEPLOY.md#migration-rollback-checklist).
