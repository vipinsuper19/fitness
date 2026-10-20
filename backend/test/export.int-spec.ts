/**
 * GET /me/export (T7 follow-up): only the caller's data, no secrets, contract-shaped, rate-limited.
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { NestExpressApplication } from '@nestjs/platform-express';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import request from 'supertest';
import { parse } from 'yaml';

import { seed } from '../prisma/seed';
import { seedId } from '../prisma/seed-data/ids';
import { EXPORT_MAX_ROWS } from '../src/me/export.service';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

const spec = parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: unknown };
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: 'openapi', components: spec.components });
const validateExport = ajv.compile({ $ref: 'openapi#/components/schemas/DataExport' });

const PASSWORD = 'correct horse battery';
const SECRET_KEYS = /passwordHash|password_hash|tokenHash|token_hash|refreshToken|accessToken|responseBody|requestHash|idempotency/i;

describeDb('GET /me/export (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const as = (token: string) => ({
    get: (url: string) => http().get(url).set('Authorization', `Bearer ${token}`),
    post: (url: string, key: string | null = randomUUID()) => {
      const r = http().post(url).set('Authorization', `Bearer ${token}`);
      return key ? r.set('Idempotency-Key', key) : r;
    },
    put: (url: string) => http().put(url).set('Authorization', `Bearer ${token}`),
    patch: (url: string) => http().patch(url).set('Authorization', `Bearer ${token}`),
  });
  const signUp = async () => {
    const email = `export-${randomUUID()}@example.in`;
    const res = await http().post('/api/v1/auth/register').send({ email, password: PASSWORD }).expect(201);
    return { token: res.body.accessToken as string, refreshToken: res.body.refreshToken as string, id: res.body.user.id as string, email };
  };

  /** Creates one of everything the export covers, returning the ids. */
  async function populate(token: string) {
    const u = as(token);
    await u.put('/api/v1/me/profile').send({ displayName: 'Asha', heightCm: 162, weightKg: 58, dailyWaterTargetMl: 2500 }).expect(200);
    const goal = (await u.post('/api/v1/goals').send({ type: 'lose_weight', startValue: 72, targetValue: 65 }).expect(201)).body;
    const draft = (await u.post('/api/v1/workout-plans/generate').send({ goal: 'gain_muscle', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 45, equipment: ['dumbbell', 'bench'] }).expect(200)).body;
    const plan = (await u.post('/api/v1/workout-plans').send(draft).expect(201)).body;
    await u.patch(`/api/v1/workout-plans/${plan.id}`).send({ ...draft, name: 'Edited plan', baseVersion: 1 }).expect(200);
    const session = (await u.post('/api/v1/workout-sessions').send({}).expect(201)).body;
    const set = (await u.post(`/api/v1/workout-sessions/${session.id}/sets`).send({ clientSetId: randomUUID(), exerciseId: seedId('exercise', 'push-up'), setIndex: 0, reps: 10, completed: true }).expect(200)).body;
    await u.post(`/api/v1/workout-sessions/${session.id}/complete`).send({}).expect(200);
    const food = (await u.post('/api/v1/foods').send({ name: `Ghar ka poha ${randomUUID().slice(0, 6)}`, servingSize: 1, servingUnit: 'plate', gramsPerServing: 150, nutrients: { energyKcal: 250, proteinG: 5, carbsG: 45, fatG: 6 }, isVegetarian: true }).expect(201)).body;
    const log = (await u.post('/api/v1/food-logs').send({ foodId: food.id, date: '2026-10-19', mealType: 'breakfast', servings: 1 }).expect(201)).body;
    const water = (await u.post('/api/v1/water-logs').send({ date: '2026-10-19', amountMl: 250 }).expect(201)).body;
    const m = (await u.post('/api/v1/body-measurements').send({ date: '2026-10-19', weightKg: 58.2 }).expect(201)).body;
    return { goal: goal.id, plan: plan.id, session: session.id, set: set.sets?.[0]?.id ?? null, food: food.id, log: log.id, water: water.id, measurement: m.id };
  }

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  it('exports every collection for the caller only, in the contract shape', async () => {
    const alice = await signUp();
    const bob = await signUp();
    const a = await populate(alice.token);
    const b = await populate(bob.token);

    const res = await as(alice.token).get('/api/v1/me/export').expect(200);
    expect(res.headers['cache-control']).toBe('no-store');
    const body = res.body;
    expect(validateExport(body)).toBe(true);
    expect(validateExport.errors ?? null).toBeNull();

    expect(body.user).toEqual({ id: alice.id, email: alice.email, displayName: 'Asha', createdAt: expect.any(String) });
    expect(body.profile).toMatchObject({ userId: alice.id, heightCm: 162, weightKg: 58 });
    expect(body.goals.map((g: { id: string }) => g.id)).toEqual([a.goal]);
    // One entry per plan version.
    expect(body.workoutPlans.map((p: { id: string; version: number }) => [p.id, p.version])).toEqual([[a.plan, 2], [a.plan, 1]]);
    expect(body.workoutPlans[0].name).toBe('Edited plan');
    expect(body.workoutSessions).toHaveLength(1);
    expect(body.workoutSessions[0]).toMatchObject({ id: a.session, status: 'completed' });
    expect(body.workoutSessions[0].sets).toHaveLength(1);
    expect(body.customFoods.map((f: { id: string }) => f.id)).toEqual([a.food]);
    expect(body.foodLogs.map((l: { id: string }) => l.id)).toEqual([a.log]);
    expect(body.waterLogs.map((w: { id: string }) => w.id)).toEqual([a.water]);
    expect(body.bodyMeasurements.map((m: { id: string }) => m.id)).toEqual([a.measurement]);
    expect(body.xpLedger).toHaveLength(1);
    expect(body.xpLedger[0]).toMatchObject({ sourceType: 'workout_session', sourceId: a.session });
    expect(body.truncated).toEqual([]);

    // Nothing of Bob's leaks in, anywhere in the document.
    const text = JSON.stringify(body);
    for (const id of [bob.id, bob.email, ...Object.values(b).filter(Boolean)]) expect(text).not.toContain(id as string);
    // Catalog foods are not "custom foods".
    expect(body.customFoods.every((f: { source: string }) => f.source === 'custom')).toBe(true);
  });

  it('never includes password hashes, refresh tokens or idempotency records', async () => {
    const u = await signUp();
    await populate(u.token);
    const body = (await as(u.token).get('/api/v1/me/export').expect(200)).body;
    const text = JSON.stringify(body);

    const user = await prisma.user.findUniqueOrThrow({ where: { id: u.id } });
    expect(text).not.toContain(user.passwordHash);
    expect(text).not.toContain(PASSWORD);
    expect(text).not.toContain(u.refreshToken);
    expect(text).not.toContain(u.token);
    for (const t of await prisma.refreshToken.findMany({ where: { userId: u.id } })) expect(text).not.toContain(t.tokenHash);
    const keys = await prisma.idempotencyKey.findMany({ where: { userId: u.id } });
    expect(keys.length).toBeGreaterThan(0);
    for (const k of keys) {
      expect(text).not.toContain(k.key);
      expect(text).not.toContain(k.requestHash);
    }
    const allKeys: string[] = [];
    JSON.stringify(body, (k, v) => (allKeys.push(k), v));
    expect(allKeys.filter((k) => SECRET_KEYS.test(k))).toEqual([]);
  });

  it('caps each collection and names the ones it cut', async () => {
    const u = await signUp();
    const base = Date.parse('2010-01-01T00:00:00Z');
    await prisma.waterLog.createMany({
      data: Array.from({ length: EXPORT_MAX_ROWS + 1 }, (_, i) => ({ userId: u.id, date: new Date(base + Math.floor(i / 3) * 86_400_000), amountMl: 100 })),
    });
    const body = (await as(u.token).get('/api/v1/me/export').expect(200)).body;
    expect(body.waterLogs).toHaveLength(EXPORT_MAX_ROWS);
    expect(body.truncated).toEqual(['waterLogs']);
    expect(body.waterLogs[0].date >= body.waterLogs.at(-1).date).toBe(true); // newest kept
  });

  it('requires a signed-in user', async () => {
    await http().get('/api/v1/me/export').expect(401);
  });

  it('is rate-limited per user with 429 and Retry-After', async () => {
    const limited = await createTestApp({ env: { EXPORT_RATE_LIMIT_PER_HOUR: '2' } });
    try {
      const h = () => request(limited.getHttpServer());
      const reg = async () =>
        (await h().post('/api/v1/auth/register').send({ email: `export-rl-${randomUUID()}@example.in`, password: PASSWORD }).expect(201)).body.accessToken as string;
      const one = await reg();
      const two = await reg();
      const get = (t: string) => h().get('/api/v1/me/export').set('Authorization', `Bearer ${t}`);
      await get(one).expect(200);
      await get(one).expect(200);
      const third = await get(one).expect(429);
      expect(third.body.code).toBe('RATE_LIMITED');
      expect(third.headers['retry-after']).toBeDefined();
      // Counted per user, not per IP: another user on the same address is unaffected.
      await get(two).expect(200);
      // Other endpoints are not touched by the export bucket.
      await h().get('/api/v1/me/profile').set('Authorization', `Bearer ${one}`).expect(200);
    } finally {
      await limited.close();
    }
  });
});
