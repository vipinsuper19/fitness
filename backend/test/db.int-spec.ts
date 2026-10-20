/**
 * Runs against a real Postgres with migrations applied (CI sets DB_TESTS=1
 * after `prisma migrate deploy`). Skipped otherwise.
 */
import { PrismaPg } from '@prisma/adapter-pg';

import { seed } from '../prisma/seed';
import { PrismaClient } from '../src/generated/prisma/client';

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

  it('keeps one idempotency record per scope and key', async () => {
    const user = await newUser('idem');
    const key = '9b2d3c1e-1111-4a2b-8c3d-123456789abc';
    const data = {
      scope: `user:${user.id}`, key, userId: user.id, method: 'POST', path: '/api/v1/water-logs',
      requestHash: 'a'.repeat(64), expiresAt: new Date(Date.now() + 86_400_000),
    };
    await prisma.idempotencyKey.create({ data });
    await expect(prisma.idempotencyKey.create({ data })).rejects.toThrow();
  });

  it('allows one active plan per user and erases everything on account deletion', async () => {
    const user = await newUser('delete');
    const plan = await prisma.workoutPlan.create({
      data: { userId: user.id, source: 'custom', versions: { create: { version: 1, name: 'Plan', weeks: 4 } } },
    });
    await prisma.userProfile.create({ data: { userId: user.id, activePlanId: plan.id } });
    await prisma.waterLog.create({ data: { userId: user.id, date: new Date('2026-10-20'), amountMl: 250 } });
    await prisma.user.delete({ where: { id: user.id } });
    expect(await prisma.workoutPlan.count({ where: { userId: user.id } })).toBe(0);
    expect(await prisma.waterLog.count({ where: { userId: user.id } })).toBe(0);
    expect(await prisma.userProfile.count({ where: { userId: user.id } })).toBe(0);
  });

  it('rejects out-of-range values in the database too', async () => {
    const user = await newUser('checks');
    await expect(prisma.waterLog.create({ data: { userId: user.id, date: new Date('2026-10-20'), amountMl: 0 } })).rejects.toThrow();
  });
});
