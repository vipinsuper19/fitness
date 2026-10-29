/**
 * Account-data audit (docs/specs/account-data-audit.md): every user-owned table is either exported (contract-validated by
 * the response checker) or deliberately excluded, and every row is gone after DELETE /me while another user's rows stay.
 * The table list is derived from Postgres (FK graph from `users`), so a new user-owned table fails this test until it is
 * added to COVERAGE below.
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { seed } from '../prisma/seed';
import { seedId } from '../prisma/seed-data/ids';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const PASSWORD = 'correct horse battery';

type Coverage = {
  /** SQL fragment selecting this table's rows owned by $1 (the user id). */
  owned: string;
  /** Export section(s) holding the data, or why the table is not exported. */
  export: string[] | { excluded: string };
  /** How DELETE /me removes it. */
  deletion: string;
};

const VERSIONS = `plan_id IN (SELECT id FROM workout_plans WHERE user_id = $1)`;
const WORKOUTS = `plan_version_id IN (SELECT id FROM workout_plan_versions WHERE ${VERSIONS})`;

/** Every user-owned table. Keep in sync with docs/specs/account-data-audit.md. */
export const COVERAGE: Record<string, Coverage> = {
  users: { owned: `id = $1`, export: ['user'], deletion: 'deleted explicitly (prisma.user.delete)' },
  user_profiles: { owned: `user_id = $1`, export: ['profile'], deletion: 'FK cascade' },
  profile_target_history: { owned: `user_id = $1`, export: ['targetHistory'], deletion: 'FK cascade' },
  notification_preferences: { owned: `user_id = $1`, export: ['notificationPreferences'], deletion: 'FK cascade' },
  refresh_tokens: { owned: `user_id = $1`, export: { excluded: 'secret: token hashes; session state, not user content' }, deletion: 'revoked explicitly, then FK cascade' },
  idempotency_keys: { owned: `user_id = $1`, export: { excluded: 'request internals (hashes, replay bodies); expire after 24 h' }, deletion: 'FK cascade' },
  fitness_goals: { owned: `user_id = $1`, export: ['goals'], deletion: 'FK cascade' },
  workout_plans: { owned: `user_id = $1`, export: ['workoutPlans'], deletion: 'FK cascade' },
  workout_plan_versions: { owned: VERSIONS, export: ['workoutPlans'], deletion: 'FK cascade via workout_plans' },
  planned_workouts: { owned: WORKOUTS, export: ['workoutPlans'], deletion: 'FK cascade via workout_plan_versions' },
  planned_exercises: { owned: `planned_workout_id IN (SELECT id FROM planned_workouts WHERE ${WORKOUTS})`, export: ['workoutPlans'], deletion: 'FK cascade via planned_workouts' },
  workout_sessions: { owned: `user_id = $1`, export: ['workoutSessions'], deletion: 'FK cascade' },
  exercise_set_logs: { owned: `user_id = $1`, export: ['workoutSessions'], deletion: 'FK cascade (user and session)' },
  session_corrections: { owned: `user_id = $1`, export: ['sessionCorrections'], deletion: 'FK cascade' },
  food_items: { owned: `owner_id = $1`, export: ['customFoods'], deletion: 'FK cascade (custom foods; catalog rows have no owner)' },
  food_logs: { owned: `user_id = $1`, export: ['foodLogs'], deletion: 'FK cascade' },
  water_logs: { owned: `user_id = $1`, export: ['waterLogs'], deletion: 'FK cascade' },
  body_measurements: { owned: `user_id = $1`, export: ['bodyMeasurements'], deletion: 'FK cascade' },
  xp_transactions: { owned: `user_id = $1`, export: ['xpLedger'], deletion: 'FK cascade' },
  user_badges: { owned: `user_id = $1`, export: ['badges'], deletion: 'FK cascade' },
  user_challenges: { owned: `user_id = $1`, export: ['challenges'], deletion: 'FK cascade' },
};

/** Tables with no user data. Anything else in the schema must be in COVERAGE. */
const NOT_USER_OWNED: Record<string, string> = {
  _prisma_migrations: 'migration bookkeeping',
  exercises: 'seeded read-only catalog',
};

describeDb('account data audit: export + deletion cover every user-owned table', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const as = (token: string) => {
    const auth = (r: request.Test) => r.set('Authorization', `Bearer ${token}`);
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string) => auth(http().post(url)).set('Idempotency-Key', randomUUID()),
      put: (url: string) => auth(http().put(url)).set('Idempotency-Key', randomUUID()),
      patch: (url: string) => auth(http().patch(url)).set('Idempotency-Key', randomUUID()),
      delete: (url: string) => auth(http().delete(url)),
    };
  };

  const signUp = async () => {
    const res = await http().post('/api/v1/auth/register').set('Idempotency-Key', randomUUID()).send({ email: `audit-${randomUUID()}@example.in`, password: PASSWORD }).expect(201);
    return { token: res.body.accessToken as string, id: res.body.user.id as string };
  };

  /** Writes at least one row into every user-owned table through the API (direct SQL only where no endpoint exists). */
  async function populate(token: string, userId: string) {
    const u = as(token);
    await u.put('/api/v1/me/profile').send({ displayName: 'Asha', heightCm: 162, weightKg: 58, dailyWaterTargetMl: 2500 }).expect(200);
    await u.patch('/api/v1/me/notification-preferences').send({ enabled: false, quietHours: { start: '23:15', end: '06:45' } }).expect(200);
    await u.post('/api/v1/goals').send({ type: 'lose_weight', startValue: 72, targetValue: 65 }).expect(201);
    const draft = (await u.post('/api/v1/workout-plans/generate').send({ goal: 'gain_muscle', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 45, equipment: ['dumbbell', 'bench'] }).expect(200)).body;
    await u.post('/api/v1/workout-plans').send(draft).expect(201);
    const session = (await u.post('/api/v1/workout-sessions').send({}).expect(201)).body;
    await u.post(`/api/v1/workout-sessions/${session.id}/sets`).send({ clientSetId: randomUUID(), exerciseId: seedId('exercise', 'push-up'), setIndex: 0, reps: 10, completed: true }).expect(200);
    await u.post(`/api/v1/workout-sessions/${session.id}/complete`).send({}).expect(200);
    const food = (await u.post('/api/v1/foods').send({ name: `Ghar ka poha ${randomUUID().slice(0, 6)}`, servingSize: 1, servingUnit: 'plate', gramsPerServing: 150, nutrients: { energyKcal: 250, proteinG: 5, carbsG: 45, fatG: 6 } }).expect(201)).body;
    await u.post('/api/v1/food-logs').send({ foodId: food.id, date: '2026-10-19', mealType: 'breakfast', servings: 1 }).expect(201);
    await u.post('/api/v1/water-logs').send({ date: '2026-10-19', amountMl: 250 }).expect(201);
    await u.post('/api/v1/body-measurements').send({ date: '2026-10-19', weightKg: 58.2 }).expect(201);
    const [template] = (await u.get('/api/v1/challenges/templates').expect(200)).body.items;
    await u.post('/api/v1/challenges').send({ templateCode: template.code, target: template.targets[0] }).expect(201);
    // A correction row needs a same-day completed session with a real XP change; the row itself is what matters here.
    await prisma.sessionCorrection.create({ data: { sessionId: session.id, userId, kind: 'correction', xpDelta: 0, idempotencyKey: randomUUID() } });
    if (!(await prisma.userBadge.count({ where: { userId } }))) {
      await prisma.userBadge.create({ data: { userId, badgeCode: 'FIRST_FOOD_LOG', catalogVersion: 1 } });
    }
  }

  const counts = async (userId: string) => {
    const out: Record<string, number> = {};
    for (const [table, c] of Object.entries(COVERAGE)) {
      const [row] = await prisma.$queryRawUnsafe<{ n: bigint }[]>(`SELECT count(*) AS n FROM "${table}" WHERE ${c.owned}`, userId);
      out[table] = Number(row?.n ?? -1);
    }
    return out;
  };

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  it('COVERAGE lists exactly the tables reachable from users by foreign keys, and nothing else is unclassified', async () => {
    const reachable = await prisma.$queryRaw<{ t: string }[]>`
      WITH RECURSIVE owned(t) AS (
        SELECT 'users'::text
        UNION
        SELECT c.conrelid::regclass::text FROM pg_constraint c JOIN owned o ON c.confrelid = o.t::regclass
        WHERE c.contype = 'f'
      ) SELECT t FROM owned ORDER BY t`;
    // Also any table with a user-ish column, even without a foreign key.
    const byColumn = await prisma.$queryRaw<{ t: string }[]>`
      SELECT DISTINCT table_name AS t FROM information_schema.columns
      WHERE table_schema = current_schema() AND column_name IN ('user_id', 'owner_id')`;
    const all = await prisma.$queryRaw<{ t: string }[]>`
      SELECT table_name AS t FROM information_schema.tables WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'`;
    const userOwned = new Set([...reachable, ...byColumn].map((r) => r.t));
    expect([...userOwned].sort()).toEqual(Object.keys(COVERAGE).sort());
    expect(all.map((r) => r.t).filter((t) => !COVERAGE[t] && !NOT_USER_OWNED[t])).toEqual([]);
  });

  it('exports every exported table, then DELETE /me leaves zero rows in every table for that user only', async () => {
    const alice = await signUp();
    const bob = await signUp();
    await populate(alice.token, alice.id);
    await populate(bob.token, bob.id);

    const before = await counts(alice.id);
    const bobBefore = await counts(bob.id);
    // Every table is seeded for both users, so the deletion assertions below are meaningful.
    expect(Object.entries(before).filter(([, n]) => n === 0)).toEqual([]);
    expect(Object.entries(bobBefore).filter(([, n]) => n === 0)).toEqual([]);

    // The response checker (test/support/contract-setup.ts) validates this body against DataExport.
    const exp = (await as(alice.token).get('/api/v1/me/export').expect(200)).body;
    for (const [table, c] of Object.entries(COVERAGE)) {
      if (!Array.isArray(c.export)) continue;
      for (const section of c.export) {
        const v = exp[section];
        expect({ table, section, present: v !== undefined && v !== null && (!Array.isArray(v) || v.length > 0) }).toEqual({ table, section, present: true });
      }
    }
    expect(exp.truncated).toEqual([]);
    expect(exp.sessionCorrections.length).toBe(before.session_corrections);
    expect(exp.xpLedger.length).toBe(before.xp_transactions);
    expect(exp.badges.length).toBe(before.user_badges);
    expect(exp.challenges.length).toBe(before.user_challenges);
    expect(exp.customFoods.length).toBe(before.food_items);
    // Excluded tables leave no trace.
    const keys = JSON.stringify(exp);
    expect(keys).not.toMatch(/tokenHash|token_hash|passwordHash|requestHash|responseBody|idempotencyKey/);

    await as(alice.token).delete('/api/v1/me').send({ password: PASSWORD }).expect(204);

    expect(await counts(alice.id)).toEqual(Object.fromEntries(Object.keys(COVERAGE).map((t) => [t, 0])));
    expect(await counts(bob.id)).toEqual(bobBefore);
    await as(bob.token).get('/api/v1/me/export').expect(200);
  });
});
