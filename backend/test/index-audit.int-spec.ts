/**
 * Index audit of the hot read paths (docs/specs/index-audit.md), against Postgres (DB_TESTS=1).
 * Seeds a realistic multi-user volume, VACUUM ANALYZEs, then EXPLAIN (ANALYZE)s the queries the
 * services run (same shape as the Prisma / raw SQL) for one heavy user, prints every plan to the
 * CI log, and asserts each one is index-backed. The XP queries are also explained with the
 * pre-audit index (swapped back inside a rolled-back transaction) to show before/after.
 * Seeded rows are removed afterwards (users cascade; exercises and the food item are deleted).
 */
import type { NestExpressApplication } from '@nestjs/platform-express';

import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

const USERS = 1000; // the first HEAVY have a year of data; the rest a month
const HEAVY = 100;
const uid = (n: number) => `a0d1a0d1-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const U = uid(1);
const FOOD = 'a0d1a0d1-0000-4000-9000-000000000001';
const EX = (n: number) => `a0d1a0d1-0000-4000-a000-${n.toString(16).padStart(12, '0')}`;
const users = `(SELECT id, row_number() OVER (ORDER BY id) n FROM users WHERE email LIKE 'index-audit-%')`;

// Rows are inserted in time order across users, as production writes them, so one user's rows
// are spread over the heap (user-major inserts would cluster them and hide heap visits).
const SEED = [
  `INSERT INTO users (id, email, password_hash, updated_at)
   SELECT ('a0d1a0d1-0000-4000-8000-' || lpad(to_hex(g), 12, '0'))::uuid, 'index-audit-' || g || '@example.test', 'x', now()
   FROM generate_series(1, ${USERS}) g`,
  `INSERT INTO food_items (id, name, serving_size, serving_unit, energy_kcal, protein_g, carbs_g, fat_g, source)
   VALUES ('${FOOD}', 'Index audit dal', 1, 'katori', 150, 9, 20, 4, 'catalog')`,
  // Diary: 4 food and 4 water entries a day.
  `INSERT INTO food_logs (id, user_id, food_id, date, meal_type, servings, energy_kcal, protein_g, carbs_g, fat_g, created_at, updated_at)
   SELECT gen_random_uuid(), u.id, '${FOOD}', DATE '2026-10-29' - d, (ARRAY['breakfast','lunch','dinner','snack']::"MealType"[])[m],
          1, 150, 9, 20, 4, (DATE '2026-10-29' - d) + make_interval(hours => 2 + m * 4), now()
   FROM ${users} u, generate_series(0, 364) d, generate_series(1, 4) m WHERE u.n <= ${HEAVY} OR d < 30
   ORDER BY d DESC, m, u.n`,
  `INSERT INTO water_logs (id, user_id, date, amount_ml, created_at, updated_at)
   SELECT gen_random_uuid(), u.id, DATE '2026-10-29' - d, 250, (DATE '2026-10-29' - d) + make_interval(hours => 2 + m * 3), now()
   FROM ${users} u, generate_series(0, 364) d, generate_series(1, 4) m WHERE u.n <= ${HEAVY} OR d < 30
   ORDER BY d DESC, m, u.n`,
  // XP ledger: 400 entries per user (~a year of sessions, badges and challenges).
  `INSERT INTO xp_transactions (id, user_id, source_type, source_id, amount, reason, created_at)
   SELECT gen_random_uuid(), u.id, (ARRAY['workout_session','adjustment','badge','challenge']::"XpSourceType"[])[1 + i % 4],
          gen_random_uuid(), 10, 'index audit', TIMESTAMPTZ '2026-10-29 06:00Z' - make_interval(hours => i * 20)
   FROM ${users} u, generate_series(1, 400) i ORDER BY i DESC, u.n`,
  // Target history: 60 dated rows per user.
  `INSERT INTO profile_target_history (user_id, effective_from, daily_calorie_target_kcal, updated_at)
   SELECT u.id, DATE '2026-10-29' - i * 6, 2000, now() FROM ${users} u, generate_series(0, 59) i ORDER BY i DESC, u.n`,
  // Strength: 20 exercises; 150 completed sessions x 6 sets (heavy users), 10 sessions (the rest).
  `INSERT INTO exercises (id, slug, name, primary_muscle, tracking_type, difficulty)
   SELECT ('a0d1a0d1-0000-4000-a000-' || lpad(to_hex(g), 12, '0'))::uuid, 'index-audit-' || g, 'Index audit ' || g, 'full_body', 'reps_weight', 'advanced'
   FROM generate_series(1, 20) g`,
  `INSERT INTO workout_sessions (id, user_id, status, started_at, completed_at, updated_at)
   SELECT gen_random_uuid(), u.id, 'completed', TIMESTAMPTZ '2026-10-29 06:00Z' - make_interval(days => s * 2, hours => 1),
          TIMESTAMPTZ '2026-10-29 06:00Z' - make_interval(days => s * 2), now()
   FROM ${users} u, generate_series(1, 150) s WHERE u.n <= ${HEAVY} OR s <= 10 ORDER BY s DESC, u.n`,
  `INSERT INTO exercise_set_logs (id, session_id, user_id, client_set_id, exercise_id, set_index, reps, weight_kg, completed, logged_at, updated_at)
   SELECT gen_random_uuid(), w.id, w.user_id, gen_random_uuid(),
          ('a0d1a0d1-0000-4000-a000-' || lpad(to_hex(1 + (abs(hashtext(w.id::text)) + k) % 20), 12, '0'))::uuid,
          k, 8, 40 + k, true, w.completed_at, now()
   FROM workout_sessions w JOIN users x ON x.id = w.user_id AND x.email LIKE 'index-audit-%', generate_series(0, 5) k
   ORDER BY w.completed_at, w.id, k`,
];

const SET_JOIN = `FROM exercise_set_logs l JOIN workout_sessions w ON w.id = l.session_id
  WHERE w.user_id = '${U}' AND w.status = 'completed' AND w.completed_at IS NOT NULL AND l.completed`;
const RANGE = `w.completed_at >= '2026-01-01' AND w.completed_at < '2026-10-30'`;

/** The service queries (same predicates and order as the Prisma / raw SQL), for user U. */
const QUERIES: Record<string, string> = {
  'diary: food logs for a day (GET /food-logs)': `SELECT * FROM food_logs WHERE user_id = '${U}' AND date = '2026-06-01' ORDER BY created_at, id`,
  'diary: water total for a day (GET /nutrition/daily)': `SELECT sum(amount_ml) FROM water_logs WHERE user_id = '${U}' AND date = '2026-06-01'`,
  'diary: 90-day food totals by date (GET /nutrition/summary)': `SELECT date, sum(energy_kcal) FROM food_logs WHERE user_id = '${U}' AND date BETWEEN '2026-08-01' AND '2026-10-29' GROUP BY date`,
  'xp: ledger first page (GET /xp/ledger)': `SELECT * FROM xp_transactions WHERE user_id = '${U}' ORDER BY created_at DESC, id DESC LIMIT 21`,
  'xp: ledger next page (cursor)': `SELECT * FROM xp_transactions WHERE user_id = '${U}' AND (created_at < '2026-06-01' OR (created_at = '2026-06-01' AND id < 'ffffffff-ffff-4fff-bfff-ffffffffffff')) ORDER BY created_at DESC, id DESC LIMIT 21`,
  // Finding, not used by the service yet: the same page as a row comparison is an index range.
  'finding: ledger next page as a row comparison': `SELECT * FROM xp_transactions WHERE user_id = '${U}' AND (created_at, id) < ('2026-06-01', 'ffffffff-ffff-4fff-bfff-ffffffffffff') ORDER BY created_at DESC, id DESC LIMIT 21`,
  'xp: balance (GET /xp/balance)': `SELECT sum(amount) FROM xp_transactions WHERE user_id = '${U}'`,
  'xp: achievements XP sum': `SELECT sum(amount) FROM xp_transactions WHERE user_id = '${U}' AND source_type IN ('workout_session', 'adjustment', 'session_correction')`,
  'target history: newest row': `SELECT * FROM profile_target_history WHERE user_id = '${U}' ORDER BY effective_from DESC LIMIT 1`,
  'target history: resolver up to a date': `SELECT * FROM profile_target_history WHERE user_id = '${U}' AND effective_from <= '2026-10-29' ORDER BY effective_from`,
  'target history: list (GET /me/target-history)': `SELECT * FROM profile_target_history WHERE user_id = '${U}' ORDER BY effective_from DESC LIMIT 101`,
  'strength: exercise list (GET /progress/exercises)': `SELECT l.exercise_id, COUNT(DISTINCT w.id), MAX(w.completed_at) AS last ${SET_JOIN} AND ${RANGE} GROUP BY l.exercise_id ORDER BY last DESC, l.exercise_id DESC LIMIT 21`,
  'strength: points for one exercise (GET /progress/exercises/{id})': `SELECT (w.completed_at AT TIME ZONE 'Asia/Kolkata')::date AS d, max(l.weight_kg), count(*) ${SET_JOIN} AND l.exercise_id = '${EX(3)}' AND ${RANGE} GROUP BY 1`,
  'strength: personal records': `SELECT l.exercise_id, max(l.weight_kg), min(w.completed_at) ${SET_JOIN} AND l.exercise_id = ANY('{${EX(3)},${EX(4)}}'::uuid[]) GROUP BY 1`,
};
const XP_QUERIES = Object.keys(QUERIES).filter((k) => k.startsWith('xp:'));

describeDb('index audit: hot read paths (EXPLAIN ANALYZE on seeded data)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const plans = new Map<string, string>();

  type Db = Pick<PrismaService, '$queryRawUnsafe'>;
  const explain = async (db: Db, sql: string) =>
    (await db.$queryRawUnsafe<Array<{ 'QUERY PLAN': string }>>(`EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) ${sql}`))
      .map((r) => r['QUERY PLAN'])
      .join('\n');
  const print = (title: string, plan: string) => process.stdout.write(`\n--- index-audit: ${title}\n${plan}\n`);

  const cleanup = async () => {
    await prisma.$executeRawUnsafe(`DELETE FROM users WHERE email LIKE 'index-audit-%'`);
    await prisma.$executeRawUnsafe(`DELETE FROM exercises WHERE slug LIKE 'index-audit-%'`);
    await prisma.$executeRawUnsafe(`DELETE FROM food_items WHERE id = '${FOOD}'`);
  };

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await cleanup();
    for (const sql of SEED) await prisma.$executeRawUnsafe(sql);
    for (const t of ['users', 'food_logs', 'water_logs', 'xp_transactions', 'profile_target_history', 'workout_sessions', 'exercise_set_logs']) {
      await prisma.$executeRawUnsafe(`VACUUM ANALYZE ${t}`);
    }
    for (const [title, sql] of Object.entries(QUERIES)) {
      plans.set(title, await explain(prisma, sql));
      print(`after: ${title}`, plans.get(title)!);
    }
  }, 240_000);

  afterAll(async () => {
    await cleanup();
    await app.close();
  }, 60_000);

  it('seeded a meaningful volume', async () => {
    const [c] = await prisma.$queryRawUnsafe<Array<Record<string, bigint>>>(`
      SELECT (SELECT count(*) FROM food_logs) food, (SELECT count(*) FROM xp_transactions) xp,
             (SELECT count(*) FROM profile_target_history) history, (SELECT count(*) FROM exercise_set_logs) sets`);
    expect(Number(c!.food)).toBeGreaterThan(250_000);
    expect(Number(c!.xp)).toBeGreaterThan(400_000 - 1);
    expect(Number(c!.history)).toBeGreaterThan(60_000 - 1);
    expect(Number(c!.sets)).toBeGreaterThan(140_000);
  });

  it('never seq-scans a per-user table on a hot path', () => {
    for (const [title, plan] of plans) expect({ title, seqScan: /Seq Scan/.test(plan) }).toEqual({ title, seqScan: false });
  });

  it('diary reads use (user_id, date)', () => {
    for (const [title, plan] of plans) {
      if (title.startsWith('diary: food') || title.startsWith('diary: 90')) expect(plan).toContain('food_logs_user_id_date_idx');
      if (title.startsWith('diary: water')) expect(plan).toContain('water_logs_user_id_date_idx');
    }
  });

  it('xp: ledger pages come in index order (no sort); balance sums are index-only', () => {
    expect(plans.get('xp: ledger first page (GET /xp/ledger)')).toMatch(/Index Scan Backward using xp_transactions_user_id_created_at_id_idx/);
    expect(plans.get('xp: ledger first page (GET /xp/ledger)')).not.toMatch(/Sort/);
    for (const t of ['xp: balance (GET /xp/balance)', 'xp: achievements XP sum']) {
      expect(plans.get(t)).toMatch(/Index Only Scan using xp_transactions_user_id_created_at_id_idx/);
    }
  });

  it('finding: the ledger cursor (OR form) filters every newer row; a row comparison would not', () => {
    expect(plans.get('xp: ledger next page (cursor)')).toMatch(/Rows Removed by Filter: [1-9]/);
    expect(plans.get('finding: ledger next page as a row comparison')).toMatch(/Index Cond: .*ROW\(created_at, id\) </);
  });

  it('target history reads use the (user_id, effective_from) primary key', () => {
    for (const [title, plan] of plans) if (title.startsWith('target history')) expect(plan).toContain('profile_target_history_pkey');
  });

  it('strength reads go through the user\'s completed sessions and the set-log session index', () => {
    for (const [title, plan] of plans) {
      if (!title.startsWith('strength')) continue;
      expect(plan).toMatch(/workout_sessions_user_id_status(_completed_at)?_idx/);
      expect(plan).toContain('exercise_set_logs_session_id_idx');
    }
  });

  it('before/after: the XP queries with the pre-audit index (user_id, created_at)', async () => {
    const before = new Map<string, string>();
    await prisma
      .$transaction(async (tx) => {
        await tx.$executeRawUnsafe(`DROP INDEX xp_transactions_user_id_created_at_id_idx`);
        await tx.$executeRawUnsafe(`CREATE INDEX xp_transactions_user_id_created_at_idx ON xp_transactions (user_id, created_at)`);
        await tx.$executeRawUnsafe(`ANALYZE xp_transactions`);
        for (const t of XP_QUERIES) before.set(t, await explain(tx, QUERIES[t]!));
        throw new Error('rollback');
      }, { timeout: 120_000 })
      .catch((e: Error) => {
        if (e.message !== 'rollback') throw e;
      });
    for (const t of XP_QUERIES) print(`before: ${t}`, before.get(t)!);
    // The old index: the ledger needs a sort for the id tie-break; the sums visit the heap.
    expect(before.get('xp: ledger first page (GET /xp/ledger)')).toMatch(/Sort/);
    expect(before.get('xp: balance (GET /xp/balance)')).not.toMatch(/Index Only Scan/);
    // And the swap was rolled back.
    const [idx] = await prisma.$queryRawUnsafe<Array<{ n: bigint }>>(
      `SELECT count(*) n FROM pg_indexes WHERE indexname = 'xp_transactions_user_id_created_at_id_idx'`);
    expect(Number(idx!.n)).toBe(1);
  }, 180_000);
});
