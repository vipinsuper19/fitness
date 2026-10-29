/**
 * Purge of expired rows: `DATABASE_URL=... npm run purge:expired [-- --execute]` (docs/DEPLOY.md).
 *
 * DRY-RUN BY DEFAULT: without `--execute` it only counts. Output is per-table counts and cutoffs, never token
 * values, hashes, ids or the database URL. Deletes run in batches (one transaction per batch) and are idempotent:
 * re-running deletes nothing new.
 *
 * - `idempotency_keys`: rows past their own `expires_at` (set to created + 24 h TTL). The service already treats
 *   these as absent, so deleting them changes no behaviour.
 * - `refresh_tokens`: rows revoked, or expired, more than `marginDays` (default 7) ago, **and only in families with
 *   no live token** (a live token = not revoked and not expired).
 *
 * Why the family rule (reuse detection): presenting a revoked refresh token is how theft is detected
 * (`TokenService.rotate` → REFRESH_TOKEN_REUSED → the whole family is revoked). If an old, revoked row were deleted
 * while its family still had a live successor, a stolen old token would come back as REFRESH_TOKEN_INVALID and the
 * attacker's or victim's live successor would stay valid: detection lost. Once a family has no live token there is
 * nothing left to revoke, so its dead rows can go; presenting one then just gets REFRESH_TOKEN_INVALID (still 401).
 * A family can't regain a live token without rotating a live one, so a family with none is safe to purge, and a
 * concurrent rotation always involves a live token, which keeps its family out of the purge.
 * - `notification_sends` (docs/specs/notification-sending.md, docs/specs/data-retention.md): rows in a FINAL state
 *   (`sent` or `failed`) created more than `notificationSendsDays` (default 90, minimum 30) ago. `pending`, `held`
 *   and `sending` rows are never touched, whatever their age. The table only exists once push sending ships; until
 *   then this step reports `table not present` and deletes nothing.
 *
 * Windows (env, read by the CLI only; values below the minimum are rejected, not clamped):
 * - `PURGE_REFRESH_TOKEN_MARGIN_DAYS` (default 7, min 1)
 * - `PURGE_NOTIFICATION_SENDS_DAYS` (default 90, min 30)
 * idempotency_keys have no window of their own: they go once past `expires_at` (created + 24 h, IDEMPOTENCY_TTL_MS).
 */
import { PrismaPg } from '@prisma/adapter-pg';

import { Prisma, PrismaClient } from '../generated/prisma/client';

export interface PurgeOptions {
  now?: Date;
  execute?: boolean;
  batchSize?: number;
  marginDays?: number;
  /** Age (days, by created_at) after which sent/failed notification_sends rows go. Default 90, minimum 30. */
  notificationSendsDays?: number;
  /** Tests only: limit both tables to these users' rows. */
  onlyUserIds?: string[];
}

export interface PurgeResult {
  execute: boolean;
  cutoffs: { idempotencyKeysExpiredBefore: string; refreshTokensDeadBefore: string; notificationSendsCreatedBefore: string };
  idempotencyKeys: { eligible: number; deleted: number };
  refreshTokens: { eligible: number; deleted: number };
  /** `present: false` when the table doesn't exist yet (push sending not built). */
  notificationSends: { present: boolean; eligible: number; deleted: number };
}

export const MIN_REFRESH_TOKEN_MARGIN_DAYS = 1;
export const MIN_NOTIFICATION_SENDS_DAYS = 30;
export const DEFAULT_NOTIFICATION_SENDS_DAYS = 90;
/** Final states only. pending / held / sending must never be purged. */
export const PURGEABLE_NOTIFICATION_STATUSES = ['sent', 'failed'] as const;

const DAY_MS = 86_400_000;

export async function purgeExpired(prisma: PrismaClient, opts: PurgeOptions = {}): Promise<PurgeResult> {
  const now = opts.now ?? new Date();
  const execute = opts.execute === true;
  const batch = opts.batchSize ?? 1000;
  const margin = opts.marginDays ?? 7;
  if (!Number.isInteger(batch) || batch < 1) throw new Error('batchSize must be a positive integer');
  const nsDays = opts.notificationSendsDays ?? DEFAULT_NOTIFICATION_SENDS_DAYS;
  if (!Number.isFinite(margin) || margin < MIN_REFRESH_TOKEN_MARGIN_DAYS) throw new Error('marginDays must be at least 1');
  if (!Number.isFinite(nsDays) || nsDays < MIN_NOTIFICATION_SENDS_DAYS) {
    throw new Error(`notificationSendsDays must be at least ${MIN_NOTIFICATION_SENDS_DAYS}`);
  }
  const cutoff = new Date(now.getTime() - margin * DAY_MS);
  const nsCutoff = new Date(now.getTime() - nsDays * DAY_MS);
  const scopeIk = opts.onlyUserIds ? Prisma.sql`AND ik.user_id = ANY(${opts.onlyUserIds}::uuid[])` : Prisma.empty;
  const scopeNs = opts.onlyUserIds ? Prisma.sql`AND ns.user_id = ANY(${opts.onlyUserIds}::uuid[])` : Prisma.empty;
  const scopeRt = opts.onlyUserIds ? Prisma.sql`AND rt.user_id = ANY(${opts.onlyUserIds}::uuid[])` : Prisma.empty;

  const ikWhere = Prisma.sql`ik.expires_at <= ${now} ${scopeIk}`;
  const rtWhere = Prisma.sql`(rt.revoked_at < ${cutoff} OR rt.expires_at < ${cutoff})
    AND NOT EXISTS (SELECT 1 FROM refresh_tokens live
                    WHERE live.family_id = rt.family_id AND live.revoked_at IS NULL AND live.expires_at > ${now})
    ${scopeRt}`;
  const nsWhere = Prisma.sql`ns.status::text IN (${Prisma.join([...PURGEABLE_NOTIFICATION_STATUSES])})
    AND ns.created_at < ${nsCutoff} ${scopeNs}`;

  const count = async (table: Prisma.Sql, where: Prisma.Sql) =>
    Number((await prisma.$queryRaw<Array<{ n: bigint }>>`SELECT count(*) AS n FROM ${table} WHERE ${where}`)[0]!.n);
  const drain = async (table: Prisma.Sql, where: Prisma.Sql) => {
    let total = 0;
    for (;;) {
      const n = await prisma.$transaction((tx) =>
        tx.$executeRaw`DELETE FROM ${table} WHERE id IN (SELECT id FROM ${table} WHERE ${where} LIMIT ${batch})`);
      total += n;
      if (n < batch) return total;
    }
  };

  const IK = Prisma.raw('idempotency_keys ik');
  const RT = Prisma.raw('refresh_tokens rt');
  const NS = Prisma.raw('notification_sends ns');
  const ikEligible = await count(IK, ikWhere);
  const rtEligible = await count(RT, rtWhere);
  const nsPresent = (await prisma.$queryRaw<Array<{ t: string | null }>>`SELECT to_regclass('notification_sends')::text AS t`)[0]!.t !== null;
  const nsEligible = nsPresent ? await count(NS, nsWhere) : 0;
  return {
    execute,
    cutoffs: {
      idempotencyKeysExpiredBefore: now.toISOString(),
      refreshTokensDeadBefore: cutoff.toISOString(),
      notificationSendsCreatedBefore: nsCutoff.toISOString(),
    },
    idempotencyKeys: { eligible: ikEligible, deleted: execute ? await drain(IK, ikWhere) : 0 },
    refreshTokens: { eligible: rtEligible, deleted: execute ? await drain(RT, rtWhere) : 0 },
    notificationSends: { present: nsPresent, eligible: nsEligible, deleted: execute && nsPresent ? await drain(NS, nsWhere) : 0 },
  };
}

export function formatResult(r: PurgeResult): string {
  return [
    r.execute ? 'Mode: EXECUTE (rows deleted)' : 'Mode: DRY RUN (nothing deleted; pass --execute to delete)',
    `Cutoff idempotency_keys: expires_at <= ${r.cutoffs.idempotencyKeysExpiredBefore}`,
    `Cutoff refresh_tokens: revoked or expired before ${r.cutoffs.refreshTokensDeadBefore}, families with no live token only`,
    `idempotency_keys: ${r.idempotencyKeys.eligible} eligible, ${r.idempotencyKeys.deleted} deleted`,
    `Cutoff notification_sends: status sent|failed and created_at before ${r.cutoffs.notificationSendsCreatedBefore}`,
    `idempotency_keys: ${r.idempotencyKeys.eligible} eligible, ${r.idempotencyKeys.deleted} deleted`,
    `refresh_tokens: ${r.refreshTokens.eligible} eligible, ${r.refreshTokens.deleted} deleted`,
    r.notificationSends.present
      ? `notification_sends: ${r.notificationSends.eligible} eligible, ${r.notificationSends.deleted} deleted`
      : 'notification_sends: table not present, skipped',
  ].join('\n');
}

/** Reads a whole-day window from env; unset/empty = default; anything else must be an integer >= min. */
export function windowFromEnv(name: string, def: number, min: number, env: NodeJS.ProcessEnv = process.env): number {
  const raw = env[name];
  if (raw === undefined || raw.trim() === '') return def;
  if (!/^\d+$/.test(raw.trim())) throw new Error(`${name} must be a whole number of days`);
  const n = Number(raw.trim());
  if (n < min) throw new Error(`${name} must be at least ${min} (got ${n})`);
  return n;
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const unknown = args.filter((a) => a !== '--execute');
  if (unknown.length) throw new Error(`Unknown argument(s): ${unknown.join(' ')} (only --execute is accepted)`);
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) throw new Error('DATABASE_URL is not set');
  const prisma = new PrismaClient({ adapter: new PrismaPg({ connectionString }) });
  try {
    const marginDays = windowFromEnv('PURGE_REFRESH_TOKEN_MARGIN_DAYS', 7, MIN_REFRESH_TOKEN_MARGIN_DAYS);
    const notificationSendsDays = windowFromEnv('PURGE_NOTIFICATION_SENDS_DAYS', DEFAULT_NOTIFICATION_SENDS_DAYS, MIN_NOTIFICATION_SENDS_DAYS);
    console.log(formatResult(await purgeExpired(prisma, { execute: args.includes('--execute'), marginDays, notificationSendsDays })));
  } finally {
    await prisma.$disconnect();
  }
}

if (require.main === module) {
  main().catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
