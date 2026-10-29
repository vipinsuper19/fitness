/**
 * Index review of the hot paths (docs/perf/index-review.md), against Postgres (DB_TESTS=1).
 * Seeds one user with a year of sessions, sets, nutrition, XP, badges and challenges plus 1000
 * other users with a month (test/support/index-review-sql.ts), VACUUM ANALYZEs, then
 * EXPLAIN (ANALYZE, BUFFERS)s each hot path and prints the plans to the CI log. The pre-review
 * index set is restored inside a rolled-back transaction to print the before plans.
 * Seeded rows are removed afterwards (users cascade; exercises and the food item are deleted).
 */
import type { NestExpressApplication } from '@nestjs/platform-express';

import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';
import { ADDED, CLEANUP, DELETE_ME, DROPPED, QUERIES, SEED, TABLES, TO_BEFORE } from './support/index-review-sql';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

/** Planner choice, not a missing index: see docs/perf/index-review.md ("badges: completed-set count"). */
const SEQ_SCAN_OK = new Set(['badges: completed-set count']);
const FK = Object.keys(QUERIES).filter((k) => k.startsWith('fk:'));

describeDb('index review: hot paths (EXPLAIN ANALYZE on seeded data)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const after = new Map<string, string>();

  type Db = Pick<PrismaService, '$queryRawUnsafe'>;
  const explain = async (db: Db, sql: string) =>
    (await db.$queryRawUnsafe<Array<{ 'QUERY PLAN': string }>>(`EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) ${sql}`))
      .map((r) => r['QUERY PLAN'])
      .join('\n');
  const print = (title: string, plan: string) => process.stdout.write(`\n--- index-review: ${title}\n${plan}\n`);
  const ms = (plan: string) => Number(/Execution Time: ([\d.]+) ms/.exec(plan)?.[1]);

  /** Runs `fn` in a transaction that is always rolled back. */
  const rolledBack = async (fn: (tx: Db & Pick<PrismaService, '$executeRawUnsafe'>) => Promise<void>) => {
    await prisma
      .$transaction(async (tx) => {
        await fn(tx);
        throw new Error('rollback');
      }, { timeout: 120_000 })
      .catch((e: Error) => {
        if (e.message !== 'rollback') throw e;
      });
  };

  const cleanup = async () => {
    for (const sql of CLEANUP) await prisma.$executeRawUnsafe(sql);
  };

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await cleanup();
    for (const sql of SEED) await prisma.$executeRawUnsafe(sql);
    for (const t of TABLES) await prisma.$executeRawUnsafe(`VACUUM ANALYZE ${t}`);
    for (const [title, sql] of Object.entries(QUERIES)) {
      await prisma.$queryRawUnsafe(sql); // warm
      after.set(title, await explain(prisma, sql));
      print(`after: ${title}`, after.get(title)!);
    }
    await rolledBack(async (tx) => {
      after.set('DELETE /me', await explain(tx, DELETE_ME));
    });
    print('after: DELETE /me (another user)', after.get('DELETE /me')!);
  }, 300_000);

  afterAll(async () => {
    await cleanup();
    await app.close();
  }, 60_000);

  it('the review\'s indexes exist and the redundant ones are gone', async () => {
    const rows = await prisma.$queryRawUnsafe<Array<{ indexname: string }>>(
      `SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ANY($1::text[])`,
      [...ADDED, ...DROPPED],
    );
    expect(rows.map((r) => r.indexname).sort()).toEqual([...ADDED].sort());
    const defs = await prisma.$queryRawUnsafe<Array<{ indexname: string; indexdef: string }>>(
      `SELECT indexname, indexdef FROM pg_indexes WHERE indexname = ANY($1::text[]) ORDER BY indexname`,
      ADDED,
    );
    expect(Object.fromEntries(defs.map((d) => [d.indexname, d.indexdef.replace(/^.* USING /, '')]))).toEqual({
      idempotency_keys_user_id_idx: 'btree (user_id)',
      workout_sessions_plan_version_id_idx: 'btree (plan_version_id)',
      workout_sessions_planned_workout_id_idx: 'btree (planned_workout_id)',
    });
  });

  it('seeded a meaningful volume', async () => {
    const [c] = await prisma.$queryRawUnsafe<Array<Record<string, bigint>>>(`
      SELECT (SELECT count(*) FROM workout_sessions) sessions, (SELECT count(*) FROM exercise_set_logs) sets,
             (SELECT count(*) FROM food_logs) food, (SELECT count(*) FROM xp_transactions) xp`);
    expect(Number(c!.sessions)).toBeGreaterThan(15_000);
    expect(Number(c!.sets)).toBeGreaterThan(200_000);
    expect(Number(c!.food)).toBeGreaterThan(120_000);
    expect(Number(c!.xp)).toBeGreaterThan(25_000);
  });

  it('no hot path seq-scans a per-user table', () => {
    for (const [title, plan] of after) {
      if (SEQ_SCAN_OK.has(title) || title === 'DELETE /me') continue;
      expect({ title, seqScan: /Seq Scan/.test(plan) }).toEqual({ title, seqScan: false });
    }
    // The planner may hash-join the sessions side at this size, but the user's sets come from an index.
    expect(after.get('badges: completed-set count')).toMatch(/Index (Only )?Scan.* on exercise_set_logs|Bitmap Index Scan on exercise_set_logs/);
  });

  it('the dropped indexes are not missed: their queries use the composites', () => {
    for (const [title, plan] of after) {
      if (/^(today: (sessions|streak)|sessions: history page|progression: history)/.test(title)) {
        expect({ title, uses: plan.includes('workout_sessions_user_id_status_completed_at_idx') }).toEqual({ title, uses: true });
      }
    }
    expect(after.get('progression: plan')).toMatch(/workout_plans_(pkey|user_id_archived_at_idx)/);
  });

  it('DELETE /me FK checks use the new indexes', () => {
    expect(after.get('fk: sessions by plan version (SET NULL)')).toContain('workout_sessions_plan_version_id_idx');
    expect(after.get('fk: sessions by planned workout (SET NULL)')).toContain('workout_sessions_planned_workout_id_idx');
    expect(after.get('fk: idempotency keys by user (CASCADE)')).toContain('idempotency_keys_user_id_idx');
  });

  it('before/after: the FK checks and DELETE /me with the pre-review index set', async () => {
    const before = new Map<string, string>();
    await rolledBack(async (tx) => {
      for (const sql of TO_BEFORE) await tx.$executeRawUnsafe(sql);
      for (const t of ['workout_sessions', 'workout_plans', 'idempotency_keys']) await tx.$executeRawUnsafe(`ANALYZE ${t}`);
      for (const [title, sql] of Object.entries(QUERIES)) before.set(title, await explain(tx, sql));
      before.set('DELETE /me', await explain(tx, DELETE_ME));
    });
    for (const [title, plan] of before) print(`before: ${title}`, plan);
    const summary = [...after.keys()].map((t) => `${t}: ${ms(before.get(t)!)} ms -> ${ms(after.get(t)!)} ms`).join('\n');
    process.stdout.write(`\n--- index-review: timings (before -> after)\n${summary}\n`);
    // Before: every FK check seq-scanned its table.
    for (const t of FK) expect({ t, seqScan: /Seq Scan/.test(before.get(t)!) }).toEqual({ t, seqScan: true });
    // And the swap was rolled back.
    const [n] = await prisma.$queryRawUnsafe<Array<{ n: bigint }>>(`SELECT count(*) n FROM pg_indexes WHERE indexname = ANY($1::text[])`, ADDED);
    expect(Number(n!.n)).toBe(ADDED.length);
  }, 240_000);
});
