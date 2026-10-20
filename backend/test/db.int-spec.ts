/**
 * Runs against a real Postgres with migrations applied (CI sets DB_TESTS=1
 * after `prisma migrate deploy`). Skipped otherwise.
 */
import { PrismaPg } from '@prisma/adapter-pg';

import { seed } from '../prisma/seed';
import { PrismaClient } from '../src/generated/prisma/client';
import {
  IdempotencyConflictException,
  IdempotencyService,
  requestHash,
} from '../src/idempotency/idempotency.service';
import type { PrismaService } from '../src/prisma/prisma.service';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

describeDb('database schema (migrated Postgres)', () => {
  let prisma: PrismaClient;

  beforeAll(() => {
    prisma = new PrismaClient({ adapter: new PrismaPg({ connectionString: process.env.DATABASE_URL! }) });
  });
  afterAll(async () => {
    await prisma.$disconnect();
  });

  async function newUser(tag: string) {
    return prisma.user.create({
      data: { email: `${tag}-${Date.now()}-${Math.random().toString(36).slice(2)}@test.local`, passwordHash: 'x' },
    });
  }

  it('seeds idempotently', async () => {
    const first = await seed(prisma);
    const exercises = await prisma.exercise.count();
    const foods = await prisma.foodItem.count({ where: { source: 'catalog' } });
    await seed(prisma);
    expect(await prisma.exercise.count()).toBe(exercises);
    expect(await prisma.foodItem.count({ where: { source: 'catalog' } })).toBe(foods);
    expect(exercises).toBeGreaterThanOrEqual(first.exercises);
    expect(foods).toBeGreaterThanOrEqual(first.foods);
  });

  it('defaults a profile to India and metric', async () => {
    const user = await newUser('profile');
    const profile = await prisma.userProfile.create({ data: { userId: user.id } });
    expect(profile).toMatchObject({ timezone: 'Asia/Kolkata', locale: 'en-IN', currency: 'INR', unitSystem: 'metric' });
    await expect(
      prisma.$executeRaw`UPDATE user_profiles SET currency = 'USD' WHERE user_id = ${user.id}::uuid`,
    ).rejects.toThrow();
  });

  it('never re-awards a reversed XP award, and reverses an award only once', async () => {
    const user = await newUser('xp-reversal');
    const sessionId = '2f1c1d3e-5b7a-4c8e-9a2f-3d4b5c6e7f81';
    const award = await prisma.xPTransaction.create({
      data: { userId: user.id, sourceType: 'workout_session', sourceId: sessionId, amount: 40, reason: 'workout' },
    });
    await prisma.xPTransaction.create({
      data: { userId: user.id, sourceType: 'adjustment', sourceId: award.id, amount: -40, reason: 'reversal' },
    });
    await expect(
      prisma.xPTransaction.create({
        data: { userId: user.id, sourceType: 'adjustment', sourceId: award.id, amount: -40, reason: 'second reversal' },
      }),
    ).rejects.toThrow();
    await expect(
      prisma.xPTransaction.create({
        data: { userId: user.id, sourceType: 'workout_session', sourceId: sessionId, amount: 40, reason: 're-award' },
      }),
    ).rejects.toThrow();
    const total = await prisma.xPTransaction.aggregate({ where: { userId: user.id }, _sum: { amount: true } });
    expect(total._sum.amount).toBe(0);
  });

  it('awards XP at most once per source and rejects negative workout XP', async () => {
    const user = await newUser('xp');
    const sourceId = '7c9e6679-7425-40de-944b-e07fc1f90ae7';
    await prisma.xPTransaction.create({ data: { userId: user.id, sourceType: 'workout_session', sourceId, amount: 50, reason: 'workout' } });
    await expect(
      prisma.xPTransaction.create({ data: { userId: user.id, sourceType: 'workout_session', sourceId, amount: 50, reason: 'retry' } }),
    ).rejects.toThrow();
    await expect(
      prisma.xPTransaction.create({ data: { userId: user.id, sourceType: 'workout_session', sourceId: user.id, amount: -5, reason: 'bad' } }),
    ).rejects.toThrow();
  });

  it('upserts set logs by client set id and stores one row', async () => {
    const user = await newUser('sets');
    const exercise = await prisma.exercise.findFirstOrThrow();
    const session = await prisma.workoutSession.create({ data: { userId: user.id, startedAt: new Date() } });
    const clientSetId = '1b4e28ba-2fa1-41d2-883f-0016d3cca427';
    const where = { userId_clientSetId: { userId: user.id, clientSetId } };
    const base = { sessionId: session.id, userId: user.id, clientSetId, exerciseId: exercise.id, setIndex: 0 };
    await prisma.exerciseSetLog.upsert({ where, create: { ...base, reps: 8, completed: true }, update: { reps: 8 } });
    await prisma.exerciseSetLog.upsert({ where, create: { ...base, reps: 10, completed: true }, update: { reps: 10 } });
    const rows = await prisma.exerciseSetLog.findMany({ where: { sessionId: session.id } });
    expect(rows).toHaveLength(1);
    expect(rows[0]?.reps).toBe(10);
  });

  it('rejects out-of-range values in the database too', async () => {
    const user = await newUser('checks');
    await expect(prisma.waterLog.create({ data: { userId: user.id, date: new Date('2026-10-20'), amountMl: 0 } })).rejects.toThrow();
  });

  describe('idempotency (insert-first)', () => {
    let idem: IdempotencyService;
    beforeAll(() => {
      idem = new IdempotencyService(prisma as unknown as PrismaService);
    });
    const uuid = () => crypto.randomUUID();

    it('lets exactly one of many parallel same-key requests run', async () => {
      const user = await newUser('idem-parallel');
      const req = {
        scope: `user:${user.id}`, userId: user.id, key: uuid(), method: 'POST',
        path: '/api/v1/water-logs', requestHash: requestHash({ date: '2026-10-20', amountMl: 250 }),
      };
      const results = await Promise.allSettled(Array.from({ length: 8 }, () => idem.begin(req)));
      const proceeded = results.filter((r) => r.status === 'fulfilled' && r.value.kind === 'proceed');
      const inProgress = results.filter(
        (r) => r.status === 'rejected' && (r.reason as IdempotencyConflictException).code === 'IDEMPOTENCY_IN_PROGRESS',
      );
      expect(proceeded).toHaveLength(1);
      expect(inProgress).toHaveLength(7);
      expect(await prisma.idempotencyKey.count({ where: { scope: req.scope, key: req.key } })).toBe(1);

      const first = proceeded[0] as PromiseFulfilledResult<{ kind: 'proceed'; recordId: string }>;
      await idem.complete(first.value.recordId, { status: 201, body: { id: 'w1', amountMl: 250 } });
      await expect(idem.begin(req)).resolves.toEqual({ kind: 'replay', status: 201, body: { id: 'w1', amountMl: 250 } });
    });

    it('rejects a reused key on a different body, method or path, and never replays it', async () => {
      const user = await newUser('idem-reuse');
      const req = {
        scope: `user:${user.id}`, userId: user.id, key: uuid(), method: 'POST',
        path: '/api/v1/water-logs', requestHash: requestHash({ amountMl: 250, date: '2026-10-20' }),
      };
      const started = await idem.begin(req);
      if (started.kind !== 'proceed') throw new Error('expected proceed');
      await idem.complete(started.recordId, { status: 201, body: { id: 'w2' } });
      // Same body with keys reordered is the same request.
      expect((await idem.begin({ ...req, requestHash: requestHash({ date: '2026-10-20', amountMl: 250 }) })).kind).toBe('replay');
      for (const other of [
        { ...req, requestHash: requestHash({ amountMl: 500, date: '2026-10-20' }) },
        { ...req, path: '/api/v1/food-logs' },
        { ...req, method: 'PATCH' },
      ]) {
        await expect(idem.begin(other)).rejects.toMatchObject({ code: 'IDEMPOTENCY_KEY_REUSED' });
      }
    });

    it('frees the key when the handler fails', async () => {
      const user = await newUser('idem-abandon');
      const req = { scope: `user:${user.id}`, userId: user.id, key: uuid(), method: 'POST', path: '/api/v1/goals', requestHash: requestHash({}) };
      const first = await idem.begin(req);
      if (first.kind !== 'proceed') throw new Error('expected proceed');
      await idem.abandon(first.recordId);
      expect((await idem.begin(req)).kind).toBe('proceed');
    });

    it('never stores sign-up tokens, reruns a finished sign-up, and erases the row with the account', async () => {
      const req = { scope: 'anon:register', key: uuid(), method: 'POST', path: '/api/v1/auth/register', requestHash: requestHash({ email: 'a@b.in' }) };
      const first = await idem.begin(req);
      if (first.kind !== 'proceed') throw new Error('expected proceed');
      await expect(idem.begin(req)).rejects.toMatchObject({ code: 'IDEMPOTENCY_IN_PROGRESS' });
      const user = await newUser('idem-register');
      await idem.complete(first.recordId, { status: 201, body: { accessToken: 'secret', refreshToken: 'secret' }, userId: user.id });
      const row = await prisma.idempotencyKey.findUniqueOrThrow({ where: { id: first.recordId } });
      expect(row.responseBody).toBeNull();
      expect(row.userId).toBe(user.id);
      // A retry re-runs the handler (409 EMAIL_TAKEN or fresh tokens); nothing is replayed.
      await expect(idem.begin(req)).resolves.toEqual({ kind: 'rerun' });
      await prisma.user.delete({ where: { id: user.id } });
      expect(await prisma.idempotencyKey.count({ where: { id: first.recordId } })).toBe(0);
    });

    it('rejects a stored body for auth paths or anonymous scopes in the database', async () => {
      const user = await newUser('idem-check');
      const base = { userId: user.id, method: 'POST', requestHash: 'a'.repeat(64), status: 'completed' as const, responseStatus: 200, expiresAt: new Date(Date.now() + 60_000) };
      await expect(
        prisma.idempotencyKey.create({ data: { ...base, scope: `user:${user.id}`, key: uuid(), path: '/api/v1/auth/login', responseBody: { accessToken: 'x' } } }),
      ).rejects.toThrow();
      await expect(
        prisma.idempotencyKey.create({ data: { ...base, scope: 'anon:register', key: uuid(), path: '/api/v1/goals', responseBody: { ok: true } } }),
      ).rejects.toThrow();
    });

    it('purges expired keys (including orphaned sign-up rows) and treats an expired key as free', async () => {
      const req = { scope: 'anon:register', key: uuid(), method: 'POST', path: '/api/v1/auth/register', requestHash: requestHash({ email: 'old@b.in' }) };
      const past = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000);
      await idem.begin(req, past); // never completed: orphaned
      expect((await idem.begin(req)).kind).toBe('proceed'); // expired row replaced
      const stale = { ...req, key: uuid() };
      await idem.begin(stale, past);
      expect(await idem.purgeExpired()).toBeGreaterThanOrEqual(1);
      expect(await prisma.idempotencyKey.count({ where: { scope: 'anon:register', key: stale.key } })).toBe(0);
    });
  });

  it('erases every user-owned table on account deletion, including custom foods that were logged', async () => {
    const user = await newUser('delete-all');
    const other = await newUser('delete-other');
    const exercise = await prisma.exercise.findFirstOrThrow();
    const plan = await prisma.workoutPlan.create({
      data: {
        userId: user.id, source: 'custom',
        versions: { create: { version: 1, name: 'Plan', weeks: 4, workouts: { create: { dayOfWeek: 1, name: 'A', exercises: { create: { exerciseId: exercise.id, order: 0, sets: 3 } } } } } },
      },
      include: { versions: { include: { workouts: true } } },
    });
    const version = plan.versions[0]!;
    await prisma.userProfile.create({ data: { userId: user.id, activePlanId: plan.id } });
    await prisma.refreshToken.create({ data: { userId: user.id, familyId: crypto.randomUUID(), tokenHash: 'f'.repeat(64), expiresAt: new Date(Date.now() + 60_000) } });
    await prisma.fitnessGoal.create({ data: { userId: user.id, type: 'lose_weight', targetValue: 70 } });
    const session = await prisma.workoutSession.create({
      data: { userId: user.id, planVersionId: version.id, plannedWorkoutId: version.workouts[0]!.id, startedAt: new Date() },
    });
    await prisma.exerciseSetLog.create({ data: { sessionId: session.id, userId: user.id, clientSetId: crypto.randomUUID(), exerciseId: exercise.id, setIndex: 0, reps: 10, completed: true } });
    const customFood = await prisma.foodItem.create({
      data: { ownerId: user.id, name: 'My poha', servingSize: 1, servingUnit: 'katori', energyKcal: 250, proteinG: 5, carbsG: 40, fatG: 8, source: 'custom' },
    });
    const catalogFood = await prisma.foodItem.findFirstOrThrow({ where: { source: 'catalog' } });
    const logBase = { date: new Date('2026-10-20'), mealType: 'breakfast' as const, servings: 1, energyKcal: 250, proteinG: 5, carbsG: 40, fatG: 8 };
    await prisma.foodLog.create({ data: { ...logBase, userId: user.id, foodId: customFood.id } });
    await prisma.foodLog.create({ data: { ...logBase, userId: user.id, foodId: catalogFood.id } });
    await prisma.waterLog.create({ data: { userId: user.id, date: new Date('2026-10-20'), amountMl: 250 } });
    await prisma.bodyMeasurement.create({ data: { userId: user.id, date: new Date('2026-10-20'), weightKg: 72.5 } });
    await prisma.xPTransaction.create({ data: { userId: user.id, sourceType: 'workout_session', sourceId: session.id, amount: 30, reason: 'workout' } });
    await prisma.idempotencyKey.create({
      data: { scope: `user:${user.id}`, key: crypto.randomUUID(), userId: user.id, method: 'POST', path: '/api/v1/water-logs', requestHash: 'b'.repeat(64), expiresAt: new Date(Date.now() + 60_000) },
    });
    await prisma.waterLog.create({ data: { userId: other.id, date: new Date('2026-10-20'), amountMl: 500 } });

    // A logged custom food cannot be deleted on its own (diary history is kept)...
    await expect(prisma.foodItem.delete({ where: { id: customFood.id } })).rejects.toThrow();
    // ...but deleting the account removes it together with its logs.
    await prisma.user.delete({ where: { id: user.id } });

    const counts = await Promise.all([
      prisma.userProfile.count({ where: { userId: user.id } }),
      prisma.refreshToken.count({ where: { userId: user.id } }),
      prisma.fitnessGoal.count({ where: { userId: user.id } }),
      prisma.workoutPlan.count({ where: { userId: user.id } }),
      prisma.workoutPlanVersion.count({ where: { planId: plan.id } }),
      prisma.plannedWorkout.count({ where: { planVersionId: version.id } }),
      prisma.workoutSession.count({ where: { userId: user.id } }),
      prisma.exerciseSetLog.count({ where: { userId: user.id } }),
      prisma.foodItem.count({ where: { ownerId: user.id } }),
      prisma.foodLog.count({ where: { userId: user.id } }),
      prisma.waterLog.count({ where: { userId: user.id } }),
      prisma.bodyMeasurement.count({ where: { userId: user.id } }),
      prisma.xPTransaction.count({ where: { userId: user.id } }),
      prisma.idempotencyKey.count({ where: { OR: [{ userId: user.id }, { scope: `user:${user.id}` }] } }),
    ]);
    expect(counts).toEqual(new Array(counts.length).fill(0));
    // Shared catalog rows and other users' data are untouched.
    expect(await prisma.foodItem.count({ where: { id: catalogFood.id } })).toBe(1);
    expect(await prisma.exercise.count({ where: { id: exercise.id } })).toBe(1);
    expect(await prisma.waterLog.count({ where: { userId: other.id } })).toBe(1);
  });
});
