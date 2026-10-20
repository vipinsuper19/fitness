/**
 * T16b (QA): cross-cutting end-to-end checks over HTTP + Postgres (DB_TESTS=1).
 * - every user-owned route rejects unauthenticated / bad tokens (401)
 * - a second user can neither read nor change the first user's data (404)
 * - writes are safe to retry with an Idempotency-Key, keys are per user
 * - XP is awarded once per session, even under concurrent completes
 * - main happy path: register -> profile -> goal -> plan -> session -> XP -> food -> progress -> export -> delete
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

const PUSH_UP = seedId('exercise', 'push-up');
const PASSWORD = 'correct horse battery';
const DAY = '2026-10-15';

describeDb('T16b QA: access rules, safe retries, duplicate XP, happy path', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const as = (token: string) => {
    const a = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    return {
      get: (u: string) => a(http().get(u)),
      put: (u: string, key = randomUUID()) => a(http().put(u)).set('Idempotency-Key', key),
      post: (u: string, key: string | null = randomUUID()) => (key ? a(http().post(u)).set('Idempotency-Key', key) : a(http().post(u))),
      patch: (u: string, key = randomUUID()) => a(http().patch(u)).set('Idempotency-Key', key),
      delete: (u: string, key = randomUUID()) => a(http().delete(u)).set('Idempotency-Key', key),
    };
  };
  const signUp = async () => {
    const email = `qa-${randomUUID()}@example.in`;
    const res = await http().post('/api/v1/auth/register').send({ email, password: PASSWORD }).expect(201);
    return { email, token: res.body.accessToken as string, refresh: res.body.refreshToken as string, id: res.body.user.id as string };
  };
  const completedSession = async (token: string) => {
    const s = (await as(token).post('/api/v1/workout-sessions').send({}).expect(201)).body;
    await as(token).post(`/api/v1/workout-sessions/${s.id}/sets`).send({ clientSetId: randomUUID(), exerciseId: PUSH_UP, setIndex: 0, reps: 10, completed: true }).expect(200);
    return s.id as string;
  };

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  describe('unauthenticated access', () => {
    const id = randomUUID();
    const routes: Array<[string, string]> = [
      ['get', '/api/v1/me/export'], ['delete', '/api/v1/me'], ['get', '/api/v1/me/profile'], ['put', '/api/v1/me/profile'],
      ['get', '/api/v1/goals'], ['post', '/api/v1/goals'], ['patch', `/api/v1/goals/${id}`], ['delete', `/api/v1/goals/${id}`],
      ['get', '/api/v1/exercises'], ['get', `/api/v1/exercises/${PUSH_UP}`],
      ['post', '/api/v1/workout-plans/generate'], ['post', '/api/v1/workout-plans'], ['get', '/api/v1/workout-plans/active'],
      ['get', `/api/v1/workout-plans/${id}`], ['patch', `/api/v1/workout-plans/${id}`], ['post', `/api/v1/workout-plans/${id}/activate`],
      ['post', '/api/v1/workout-sessions'], ['get', '/api/v1/workout-sessions/history'], ['get', `/api/v1/workout-sessions/${id}`],
      ['patch', `/api/v1/workout-sessions/${id}`], ['post', `/api/v1/workout-sessions/${id}/sets`], ['post', `/api/v1/workout-sessions/${id}/complete`],
      ['get', '/api/v1/foods/search?query=roti'], ['post', '/api/v1/foods'], ['get', '/api/v1/food-logs?date=2026-10-15'], ['post', '/api/v1/food-logs'],
      ['patch', `/api/v1/food-logs/${id}`], ['delete', `/api/v1/food-logs/${id}`], ['post', '/api/v1/water-logs'], ['delete', `/api/v1/water-logs/${id}`],
      ['get', `/api/v1/nutrition/daily?date=${DAY}`], ['get', '/api/v1/body-measurements'], ['post', '/api/v1/body-measurements'],
      ['delete', `/api/v1/body-measurements/${id}`], ['get', '/api/v1/progress/summary'], ['get', '/api/v1/xp/balance'], ['get', '/api/v1/xp/ledger'],
    ];
    it.each(routes)('%s %s -> 401 without a token, with a garbage token and with a refresh token', async (method, url) => {
      const call = () => (http() as unknown as Record<string, (u: string) => request.Test>)[method]!(url).set('Idempotency-Key', randomUUID());
      const none = await call().send({}).expect(401);
      expect(none.body).toMatchObject({ code: expect.any(String), requestId: expect.any(String) });
      await call().set('Authorization', 'Bearer not.a.jwt').send({}).expect(401);
      const u = await signUp();
      await call().set('Authorization', `Bearer ${u.refresh}`).send({}).expect(401);
    });

    it('tokens stop working after logout-all style account deletion', async () => {
      const u = await signUp();
      await as(u.token).delete('/api/v1/me').send({ password: PASSWORD }).expect(204);
      await as(u.token).get('/api/v1/me/profile').expect(401);
      await http().post('/api/v1/auth/refresh').send({ refreshToken: u.refresh }).expect(401);
    });
  });

  describe('cross-user isolation', () => {
    it('user B gets 404 for every resource of user A, and A’s data is unchanged', async () => {
      const a = await signUp();
      const b = await signUp();
      const goal = (await as(a.token).post('/api/v1/goals').send({ type: 'workouts_per_week', targetValue: 3 }).expect(201)).body;
      const draft = (await as(a.token).post('/api/v1/workout-plans/generate', null).send({ goal: 'maintain', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 30 }).expect(200)).body;
      const plan = (await as(a.token).post('/api/v1/workout-plans').send(draft).expect(201)).body;
      const sessionId = await completedSession(a.token);
      const food = (await as(a.token).post('/api/v1/foods').send({ name: 'QA private snack', servingSize: 100, servingUnit: 'g', nutrients: { energyKcal: 200, proteinG: 5, carbsG: 30, fatG: 6 } }).expect(201)).body;
      const flog = (await as(a.token).post('/api/v1/food-logs').send({ foodId: food.id, date: DAY, mealType: 'lunch', servings: 1 }).expect(201)).body;
      const water = (await as(a.token).post('/api/v1/water-logs').send({ date: DAY, amountMl: 250 }).expect(201)).body;
      const meas = (await as(a.token).post('/api/v1/body-measurements').send({ date: DAY, weightKg: 70 }).expect(201)).body;

      await as(b.token).patch(`/api/v1/goals/${goal.id}`).send({ status: 'abandoned' }).expect(404);
      await as(b.token).delete(`/api/v1/goals/${goal.id}`).expect(404);
      await as(b.token).get(`/api/v1/workout-plans/${plan.id}`).expect(404);
      await as(b.token).patch(`/api/v1/workout-plans/${plan.id}`).send({ ...draft, baseVersion: 1 }).expect(404);
      await as(b.token).post(`/api/v1/workout-plans/${plan.id}/activate`).send({}).expect(404);
      await as(b.token).get(`/api/v1/workout-sessions/${sessionId}`).expect(404);
      await as(b.token).post(`/api/v1/workout-sessions/${sessionId}/complete`).send({}).expect(404);
      await as(b.token).post('/api/v1/food-logs').send({ foodId: food.id, date: DAY, mealType: 'lunch', servings: 1 }).expect(404);
      await as(b.token).patch(`/api/v1/food-logs/${flog.id}`).send({ servings: 9 }).expect(404);
      await as(b.token).delete(`/api/v1/food-logs/${flog.id}`).expect(404);
      await as(b.token).delete(`/api/v1/water-logs/${water.id}`).expect(404);
      await as(b.token).delete(`/api/v1/body-measurements/${meas.id}`).expect(404);

      // B's own lists never show A's rows.
      const bGoals = (await as(b.token).get('/api/v1/goals').expect(200)).body;
      expect(Array.isArray(bGoals.items)).toBe(true);
      expect(bGoals.items.map((g: { id: string }) => g.id)).not.toContain(goal.id);
      expect((await as(a.token).get('/api/v1/goals').expect(200)).body.items.map((g: { id: string }) => g.id)).toContain(goal.id);
      const bSearch = JSON.stringify((await as(b.token).get('/api/v1/foods/search?query=QA%20private').expect(200)).body);
      expect(bSearch).not.toContain(food.id);
      const bHistory = JSON.stringify((await as(b.token).get('/api/v1/workout-sessions/history').expect(200)).body);
      expect(bHistory).not.toContain(sessionId);
      const bDaily = (await as(b.token).get(`/api/v1/nutrition/daily?date=${DAY}`).expect(200)).body;
      expect(JSON.stringify(bDaily)).not.toContain(flog.id);
      const bExport = JSON.stringify((await as(b.token).get('/api/v1/me/export').expect(200)).body);
      for (const leaked of [a.id, a.email, goal.id, plan.id, sessionId, food.id, flog.id, water.id, meas.id]) expect(bExport).not.toContain(leaked);
      expect((await as(b.token).get('/api/v1/xp/balance').expect(200)).body.total).toBe(0);

      // A still owns everything, untouched.
      expect((await as(a.token).get(`/api/v1/workout-sessions/${sessionId}`).expect(200)).body.status).toBe('in_progress');
      expect(await prisma.foodLog.count({ where: { id: flog.id, servings: 1 } })).toBe(1);
      expect(await prisma.fitnessGoal.count({ where: { id: goal.id, userId: a.id } })).toBe(1);
    });
  });

  describe('safe retries (Idempotency-Key)', () => {
    it('replays the same response for a retried create and stores one row', async () => {
      const u = await signUp();
      const key = randomUUID();
      const body = { date: DAY, amountMl: 300 };
      const first = await as(u.token).post('/api/v1/water-logs', key).send(body).expect(201);
      const retry = await as(u.token).post('/api/v1/water-logs', key).send({ amountMl: 300, date: DAY }).expect(201);
      expect(retry.headers['idempotent-replayed']).toBe('true');
      expect(retry.body).toEqual(first.body);
      expect(await prisma.waterLog.count({ where: { userId: u.id } })).toBe(1);
    });

    it('rejects the same key with a different body (409 IDEMPOTENCY_KEY_REUSED)', async () => {
      const u = await signUp();
      const key = randomUUID();
      await as(u.token).post('/api/v1/water-logs', key).send({ date: DAY, amountMl: 300 }).expect(201);
      const r = await as(u.token).post('/api/v1/water-logs', key).send({ date: DAY, amountMl: 999 }).expect(409);
      expect(r.body.code).toBe('IDEMPOTENCY_KEY_REUSED');
      const p = await as(u.token).post('/api/v1/body-measurements', key).send({ date: DAY, weightKg: 70 }).expect(409);
      expect(p.body.code).toBe('IDEMPOTENCY_KEY_REUSED');
    });

    it('keys are scoped per user: B reusing A’s key neither replays A’s data nor is blocked', async () => {
      const a = await signUp();
      const b = await signUp();
      const key = randomUUID();
      const ra = await as(a.token).post('/api/v1/water-logs', key).send({ date: DAY, amountMl: 400 }).expect(201);
      const rb = await as(b.token).post('/api/v1/water-logs', key).send({ date: DAY, amountMl: 400 }).expect(201);
      expect(rb.headers['idempotent-replayed']).toBeUndefined();
      expect(rb.body.id).not.toBe(ra.body.id);
    });

    it('concurrent duplicates create exactly one row', async () => {
      const u = await signUp();
      const key = randomUUID();
      const results = await Promise.all(Array.from({ length: 5 }, () => as(u.token).post('/api/v1/food-logs', key).send({ foodId: seedId('food', 'roti'), date: DAY, mealType: 'dinner', servings: 1 })));
      for (const r of results) expect([201, 409]).toContain(r.status);
      expect(results.some((r) => r.status === 201)).toBe(true);
      expect(await prisma.foodLog.count({ where: { userId: u.id } })).toBe(1);
    });

    it('a retried delete is safe', async () => {
      const u = await signUp();
      const w = (await as(u.token).post('/api/v1/water-logs').send({ date: DAY, amountMl: 200 }).expect(201)).body;
      const key = randomUUID();
      await as(u.token).delete(`/api/v1/water-logs/${w.id}`, key).expect(204);
      await as(u.token).delete(`/api/v1/water-logs/${w.id}`, key).expect(204);
    });
  });

  describe('duplicate-XP prevention', () => {
    it('concurrent completes of one session award XP once', async () => {
      const u = await signUp();
      const sid = await completedSession(u.token);
      const results = await Promise.all(Array.from({ length: 6 }, () => as(u.token).post(`/api/v1/workout-sessions/${sid}/complete`).send({})));
      for (const r of results) expect([200, 409]).toContain(r.status);
      expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(1);
      const bal = (await as(u.token).get('/api/v1/xp/balance').expect(200)).body;
      const ledger = (await as(u.token).get('/api/v1/xp/ledger').expect(200)).body;
      const items = ledger.items;
      expect(items).toHaveLength(1);
      expect(typeof items[0].amount).toBe('number');
      expect(bal.total).toBe(items[0].amount);
    });

    it('two sessions earn separately; XP is never client-writable', async () => {
      const u = await signUp();
      for (let i = 0; i < 2; i += 1) {
        const sid = await completedSession(u.token);
        const bad = await as(u.token).post(`/api/v1/workout-sessions/${sid}/complete`).send({ xpAwarded: 100000 }).expect(400);
        expect(bad.body.code).toBe('VALIDATION_FAILED');
        await as(u.token).post(`/api/v1/workout-sessions/${sid}/complete`).send({}).expect(200);
      }
      expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(2);
      expect((await as(u.token).get('/api/v1/xp/balance').expect(200)).body.total).toBeLessThan(1000);
      const nf = await as(u.token).post('/api/v1/xp/ledger').send({ amount: 500 }).expect(404);
      expect(nf.body.code).toBe('NOT_FOUND');
    });

    it('other user cannot complete someone else’s session to farm XP', async () => {
      const a = await signUp();
      const b = await signUp();
      const sid = await completedSession(a.token);
      await as(b.token).post(`/api/v1/workout-sessions/${sid}/complete`).send({}).expect(404);
      expect(await prisma.xPTransaction.count({ where: { userId: { in: [a.id, b.id] } } })).toBe(0);
    });
  });

  describe('main happy path', () => {
    it('register -> login -> profile -> goal -> plan -> session -> XP -> nutrition -> progress -> export -> delete', async () => {
      const u = await signUp();
      const login = await http().post('/api/v1/auth/login').send({ email: u.email, password: PASSWORD }).expect(200);
      const t = login.body.accessToken as string;
      const refreshed = await http().post('/api/v1/auth/refresh').send({ refreshToken: login.body.refreshToken }).expect(200);
      expect(refreshed.body.refreshToken).not.toBe(login.body.refreshToken);
      await as(t).put('/api/v1/me/profile').send({ dailyCalorieTargetKcal: 2000, dailyWaterTargetMl: 2500 }).expect(200);
      await as(t).post('/api/v1/goals').send({ type: 'lose_weight', targetValue: 65, startValue: 72 }).expect(201);
      const ex = (await as(t).get('/api/v1/exercises?muscle=chest').expect(200)).body;
      expect(JSON.stringify(ex).length).toBeGreaterThan(10);
      const draft = (await as(t).post('/api/v1/workout-plans/generate', null).send({ goal: 'lose_weight', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 30 }).expect(200)).body;
      expect(await prisma.workoutPlan.count({ where: { userId: u.id } })).toBe(0); // generate never persists
      const plan = (await as(t).post('/api/v1/workout-plans').send(draft).expect(201)).body;
      const act = (await as(t).post(`/api/v1/workout-plans/${plan.id}/activate`).send({}).expect(200)).body;
      expect(act.id).toBe(plan.id);
      expect(act.isActive).toBe(true);
      expect((await as(t).get('/api/v1/workout-plans/active').expect(200)).body.id).toBe(plan.id);
      const s = (await as(t).post('/api/v1/workout-sessions').send({ plannedWorkoutId: plan.workouts[0].id, planVersion: 1 }).expect(201)).body;
      await as(t).post(`/api/v1/workout-sessions/${s.id}/sets`).send({ clientSetId: randomUUID(), exerciseId: PUSH_UP, setIndex: 0, reps: 10, completed: true }).expect(200);
      const done = (await as(t).post(`/api/v1/workout-sessions/${s.id}/complete`).send({ perceivedDifficulty: 3 }).expect(200)).body;
      expect(done.xpAwarded).toBeGreaterThan(0);
      expect((await as(t).get('/api/v1/xp/balance').expect(200)).body.total).toBe(done.xpAwarded);
      await as(t).post('/api/v1/food-logs').send({ foodId: seedId('food', 'roti'), date: DAY, mealType: 'lunch', servings: 2 }).expect(201);
      await as(t).post('/api/v1/water-logs').send({ date: DAY, amountMl: 500 }).expect(201);
      const daily = (await as(t).get(`/api/v1/nutrition/daily?date=${DAY}`).expect(200)).body;
      expect(JSON.stringify(daily)).toContain('500');
      await as(t).post('/api/v1/body-measurements').send({ date: DAY, weightKg: 71.5 }).expect(201);
      const summary = (await as(t).get('/api/v1/progress/summary?from=2026-10-01&to=2026-10-31').expect(200)).body;
      expect(JSON.stringify(summary)).toContain('71.5');
      const exp = (await as(t).get('/api/v1/me/export').expect(200)).body;
      expect(JSON.stringify(exp)).toContain(s.id);
      await as(t).delete('/api/v1/me').send({ password: PASSWORD }).expect(204);
      expect(await prisma.user.count({ where: { id: u.id } })).toBe(0);
      expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(0);
    });
  });
});
