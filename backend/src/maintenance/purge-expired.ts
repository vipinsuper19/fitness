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
 */
import { PrismaPg } from '@prisma/adapter-pg';

import { Prisma, PrismaClient } from '../generated/prisma/client';

export interface PurgeOptions {
  now?: Date;
  execute?: boolean;
  batchSize?: number;
  marginDays?: number;
  /** Tests only: limit both tables to these users' rows. */
  onlyUserIds?: string[];
}

export interface PurgeResult {
  execute: boolean;
  cutoffs: { idempotencyKeysExpiredBefore: string; refreshTokensDeadBefore: string };
  idempotencyKeys: { eligible: number; deleted: number };
  refreshTokens: { eligible: number; deleted: number };
}

const DAY_MS = 86_400_000;

export async function purgeExpired(prisma: PrismaClient, opts: PurgeOptions = {}): Promise<PurgeResult> {
  const now = opts.now ?? new Date();
  const execute = opts.execute === true;
  const batch = opts.batchSize ?? 1000;
  const margin = opts.marginDays ?? 7;
  if (!Number.isInteger(batch) || batch < 1) throw new Error('batchSize must be a positive integer');
  if (!Number.isFinite(margin) || margin < 1) throw new Error('marginDays must be at least 1');
  const cutoff = new Date(now.getTime() - margin * DAY_MS);
  const scopeIk = opts.onlyUserIds ? Prisma.sql`AND ik.user_id = ANY(${opts.onlyUserIds}::uuid[])` : Prisma.empty;
  const scopeRt = opts.onlyUserIds ? Prisma.sql`AND rt.user_id = ANY(${opts.onlyUserIds}::uuid[])` : Prisma.empty;

  const ikWhere = Prisma.sql`ik.expires_at <= ${now} ${scopeIk}`;
  const rtWhere = Prisma.sql`(rt.revoked_at < ${cutoff} OR rt.expires_at < ${cutoff})
    AND NOT EXISTS (SELECT 1 FROM refresh_tokens live
                    WHERE live.family_id = rt.family_id AND live.revoked_at IS NULL AND live.expires_at > ${now})
    ${scopeRt}`;

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
  const ikEligible = await count(IK, ikWhere);
  const rtEligible = await count(RT, rtWhere);
  return {
    execute,
    cutoffs: { idempotencyKeysExpiredBefore: now.toISOString(), refreshTokensDeadBefore: cutoff.toISOString() },
    idempotencyKeys: { eligible: ikEligible, deleted: execute ? await drain(IK, ikWhere) : 0 },
    refreshTokens: { eligible: rtEligible, deleted: execute ? await drain(RT, rtWhere) : 0 },
  };
}

export function formatResult(r: PurgeResult): string {
  return [
    r.execute ? 'Mode: EXECUTE (rows deleted)' : 'Mode: DRY RUN (nothing deleted; pass --execute to delete)',
    `Cutoff idempotency_keys: expires_at <= ${r.cutoffs.idempotencyKeysExpiredBefore}`,
    `Cutoff refresh_tokens: revoked or expired before ${r.cutoffs.refreshTokensDeadBefore}, families with no live token only`,
    `idempotency_keys: ${r.idempotencyKeys.eligible} eligible, ${r.idempotencyKeys.deleted} deleted`,
    `refresh_tokens: ${r.refreshTokens.eligible} eligible, ${r.refreshTokens.deleted} deleted`,
  ].join('\n');
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const unknown = args.filter((a) => a !== '--execute');
  if (unknown.length) throw new Error(`Unknown argument(s): ${unknown.join(' ')} (only --execute is accepted)`);
  const connectionString = process.env.DATABASE_URL;
  if (!connectionString) throw new Error('DATABASE_URL is not set');
  const prisma = new PrismaClient({ adapter: new PrismaPg({ connectionString }) });
  try {
    console.log(formatResult(await purgeExpired(prisma, { execute: args.includes('--execute') })));
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
