/**
 * T15 (backend) over HTTP against Postgres (DB_TESTS=1): body measurements
 * and the progress summary (missing data is null, never 0; IST days; streak).
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { seed } from '../prisma/seed';
import { seedId } from '../prisma/seed-data/ids';
import { localDate, prevDate } from '../src/common/time';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

describeDb('T15: body measurements and progress summary (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const api = (token: string) => {
    const auth = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    const keyed = <T extends request.Test>(r: T, key: string | null) => (key === null ? auth(r) : auth(r).set('Idempotency-Key', key));
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string, key: string | null = randomUUID()) => keyed(http().post(url), key),
      patch: (url: string, key: string | null = null) => keyed(http().patch(url), key),
      delete: (url: string, key: string | null = null) => keyed(http().delete(url), key),
    };
  };
  const signUp = async () => {
    const res = await http().post('/api/v1/auth/register').send({ email: `t15-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201);
    return { token: res.body.accessToken as string, id: res.body.user.id as string };
  };

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  describe('body measurements', () => {
    it('creates, lists newest first with validated cursors, partially updates on PATCH and deletes', async () => {
      const { token } = await signUp();
      const missingKey = await api(token).post('/api/v1/body-measurements', null).send({ date: '2026-10-01', weightKg: 70 }).expect(400);
      expect(missingKey.body.details.fields).toHaveProperty(['Idempotency-Key']);
      await api(token).post('/api/v1/body-measurements').send({ date: '2026-10-01' }).expect(400);
      await api(token).post('/api/v1/body-measurements').send({ date: '2026-10-01', weightKg: null }).expect(400);
      await api(token).post('/api/v1/body-measurements').send({ date: '2026-10-01', weightKg: 401 }).expect(400);
      await api(token).post('/api/v1/body-measurements').send({ date: '2026-10-01', bodyFatPct: 1 }).expect(400);

      const ids: string[] = [];
      for (const [date, weightKg] of [['2026-10-01', 72.4], ['2026-10-08', 71.9], ['2026-10-15', 71.2]] as const) {
        ids.push((await api(token).post('/api/v1/body-measurements').send({ date, weightKg, waistCm: 84.5 }).expect(201)).body.id);
      }
      const all = (await api(token).get('/api/v1/body-measurements').expect(200)).body;
      expect(all.items.map((m: { date: string }) => m.date)).toEqual(['2026-10-15', '2026-10-08', '2026-10-01']);
      const p1 = (await api(token).get('/api/v1/body-measurements?limit=2').expect(200)).body;
      const p2 = (await api(token).get(`/api/v1/body-measurements?limit=2&cursor=${p1.nextCursor}`).expect(200)).body;
      expect([...p1.items, ...p2.items].map((m: { id: string }) => m.id)).toEqual(all.items.map((m: { id: string }) => m.id));
      const ranged = (await api(token).get('/api/v1/body-measurements?from=2026-10-02&to=2026-10-15').expect(200)).body;
      expect(ranged.items).toHaveLength(2);
      await api(token).get('/api/v1/body-measurements?from=2026-10-15&to=2026-10-01').expect(400);
      const forge = (v: unknown) => Buffer.from(JSON.stringify(v)).toString('base64url');
      for (const c of [forge({ d: '2026-10-08', id: 'x' }), forge({ d: '2026-02-30', id: randomUUID() }), 'garbage']) {
        const r = await api(token).get(`/api/v1/body-measurements?cursor=${c}`).expect(400);
        expect(r.body.details.fields).toHaveProperty('cursor');
      }

      const url0 = `/api/v1/body-measurements/${ids[0]}`;
      // partial update keeps the other fields
      expect((await api(token).patch(url0).send({ weightKg: 72 }).expect(200)).body).toMatchObject({ date: '2026-10-01', weightKg: 72, waistCm: 84.5 });
      expect((await api(token).patch(url0).send({ date: '2026-10-02' }).expect(200)).body).toMatchObject({ date: '2026-10-02', weightKg: 72, waistCm: 84.5 });
      await api(token).patch(url0).send({ date: null }).expect(400);
      await api(token).patch(url0).send({}).expect(400);
      await api(token).patch(url0).send({ date: '9999-12-31' }).expect(400);
      // null clears
      expect((await api(token).patch(url0).send({ waistCm: null }).expect(200)).body).toMatchObject({ weightKg: 72, waistCm: null });
      // clearing the last value is rejected and leaves the entry as it was
      const last = await api(token).patch(url0).send({ weightKg: null }).expect(400);
      expect(last.body.details.fields).toHaveProperty('weightKg');
      expect((await api(token).get(url0).expect(200)).body).toMatchObject({ weightKg: 72, waistCm: null });
      await api(token).patch(url0).send({ weightKg: null, armCm: 30 }).expect(200);
      await api(token).patch(url0).send({ weightKg: 72, armCm: null, date: '2026-10-01' }).expect(200);
      await api(token).delete(`/api/v1/body-measurements/${ids[0]}`).expect(204);
      await api(token).get(`/api/v1/body-measurements/${ids[0]}`).expect(404);
    });

    it("hides another user's measurements and replays a retried create", async () => {
      const a = await signUp();
      const b = await signUp();
      const key = randomUUID();
      const m = (await api(a.token).post('/api/v1/body-measurements', key).send({ date: '2026-10-10', weightKg: 60 }).expect(201)).body;
      const again = await api(a.token).post('/api/v1/body-measurements', key).send({ date: '2026-10-10', weightKg: 60 }).expect(201);
      expect(again.body.id).toBe(m.id);
      expect(again.headers['idempotent-replayed']).toBe('true');
      await api(b.token).get(`/api/v1/body-measurements/${m.id}`).expect(404);
      await api(b.token).patch(`/api/v1/body-measurements/${m.id}`).send({ date: '2026-10-10', weightKg: 99 }).expect(404);
      await api(b.token).delete(`/api/v1/body-measurements/${m.id}`).expect(404);
      expect((await api(b.token).get('/api/v1/body-measurements').expect(200)).body.items).toEqual([]);
      expect((await api(a.token).get(`/api/v1/body-measurements/${m.id}`).expect(200)).body.weightKg).toBe(60);
    });
  });

  describe('progress summary', () => {
    /** Completes a workout through the API, then moves it (and its award) to `at`. */
    async function workoutAt(token: string, userId: string, at: Date, activeSeconds = 1800) {
      const s = (await api(token).post('/api/v1/workout-sessions').send({}).expect(201)).body;
      await api(token).post(`/api/v1/workout-sessions/${s.id}/sets`).send({ clientSetId: randomUUID(), exerciseId: seedId('exercise', 'push-up'), setIndex: 0, reps: 10, completed: true }).expect(200);
      const done = (await api(token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({}).expect(200)).body;
      const startedAt = new Date(at.getTime() - activeSeconds * 1000);
      await prisma.workoutSession.update({ where: { id: s.id }, data: { startedAt, completedAt: at, activeSeconds } });
      // The award row keeps today's createdAt: xpEarned must follow the session's completedAt.
      void userId;
      return { id: s.id as string, xp: done.xpAwarded as number };
    }

    it('rejects huge or out-of-range spans quickly instead of hanging', async () => {
      const u = await signUp();
      const t0 = Date.now();
      const r = await api(u.token).get('/api/v1/progress/summary?from=2026-01-01&to=9999-12-31').timeout(5000).expect(400);
      expect(Date.now() - t0).toBeLessThan(5000);
      expect(r.body.code).toBe('VALIDATION_FAILED');
      await api(u.token).get('/api/v1/progress/summary?from=2100-12-01&to=2100-12-31').timeout(5000).expect(200);
      await api(u.token).get('/api/v1/progress/summary?from=2100-12-31&to=2101-01-01').timeout(5000).expect(400);
      await api(u.token).get('/api/v1/progress/summary?from=1899-12-31&to=1900-01-01').timeout(5000).expect(400);
      await api(u.token).get('/api/v1/progress/summary?from=2025-01-01&to=2026-01-02').timeout(5000).expect(400);
    }, 10_000);

    it('returns one point per IST day with null for missing data, never 0', async () => {
      const u = await signUp();
      await api(u.token).post('/api/v1/body-measurements').send({ date: '2026-10-02', weightKg: 70.5 }).expect(201);
      await api(u.token).post('/api/v1/body-measurements').send({ date: '2026-10-02', weightKg: 70.1 }).expect(201); // later wins
      await api(u.token).post('/api/v1/body-measurements').send({ date: '2026-10-03', waistCm: 80 }).expect(201); // no weight
      await api(u.token).post('/api/v1/food-logs').send({ foodId: seedId('food', 'roti'), date: '2026-10-02', mealType: 'lunch', servings: 2 }).expect(201);
      // 18:45 UTC on 1 Oct is 00:15 on 2 Oct in Asia/Kolkata.
      const w1 = await workoutAt(u.token, u.id, new Date('2026-10-01T18:45:00.000Z'), 2700);
      const w2 = await workoutAt(u.token, u.id, new Date('2026-10-03T05:00:00.000Z'), 600);
      // Discarding w2 reverses its award: it no longer counts as XP earned.
      await api(u.token).delete(`/api/v1/workout-sessions/${w2.id}`, randomUUID()).expect(204);

      const s = (await api(u.token).get('/api/v1/progress/summary?from=2026-10-01&to=2026-10-03').expect(200)).body;
      expect(s).toMatchObject({ from: '2026-10-01', to: '2026-10-03' });
      expect(s.points).toEqual([
        { date: '2026-10-01', weightKg: null, bodyFatPct: null, waistCm: null, waterMl: null, workoutsCompleted: 0, activeMinutes: 0, energyKcal: null, xpEarned: 0 },
        { date: '2026-10-02', weightKg: 70.1, bodyFatPct: null, waistCm: null, waterMl: null, workoutsCompleted: 1, activeMinutes: 45, energyKcal: 240, xpEarned: w1.xp },
        { date: '2026-10-03', weightKg: null, bodyFatPct: null, waistCm: 80, waterMl: null, workoutsCompleted: 0, activeMinutes: 0, energyKcal: null, xpEarned: 0 },
      ]);
    });

    it('fills every day in the range; water, body fat and waist series; latest entry per day wins per series', async () => {
      const u = await signUp();
      const m = (body: object) => api(u.token).post('/api/v1/body-measurements').send(body).expect(201);
      await m({ date: '2026-09-02', weightKg: 80, bodyFatPct: 25, waistCm: 90 });
      await m({ date: '2026-09-02', bodyFatPct: 24.5 }); // later: body fat wins, weight and waist come from the earlier entry
      await m({ date: '2026-09-02', waistCm: 89.5 });
      await m({ date: '2026-09-05', weightKg: 79.6 });
      await api(u.token).post('/api/v1/water-logs').send({ date: '2026-09-02', amountMl: 250 }).expect(201);
      await api(u.token).post('/api/v1/water-logs').send({ date: '2026-09-02', amountMl: 500 }).expect(201);
      await api(u.token).post('/api/v1/water-logs').send({ date: '2026-09-04', amountMl: 300 }).expect(201);

      const s = (await api(u.token).get('/api/v1/progress/summary?from=2026-09-01&to=2026-09-06').expect(200)).body;
      expect(s.points.map((p: { date: string }) => p.date)).toEqual(['2026-09-01', '2026-09-02', '2026-09-03', '2026-09-04', '2026-09-05', '2026-09-06']);
      const pick = (p: Record<string, unknown>) => [p.date, p.weightKg, p.bodyFatPct, p.waistCm, p.waterMl];
      expect(s.points.map(pick)).toEqual([
        ['2026-09-01', null, null, null, null],
        ['2026-09-02', 80, 24.5, 89.5, 750],
        ['2026-09-03', null, null, null, null],
        ['2026-09-04', null, null, null, 300],
        ['2026-09-05', 79.6, null, null, null],
        ['2026-09-06', null, null, null, null],
      ]);
      // A single-day range still returns that day.
      expect((await api(u.token).get('/api/v1/progress/summary?from=2026-09-03&to=2026-09-03').expect(200)).body.points).toHaveLength(1);
    });

    it('accepts up to two decimals and stores weight at 2 dp, other measurements rounded half-up to 1 dp', async () => {
      const u = await signUp();
      const made = (await api(u.token).post('/api/v1/body-measurements').send({ date: '2026-09-10', weightKg: 72.35, bodyFatPct: 18.25, waistCm: 84.24, chestCm: 99.95, armCm: 30.05 }).expect(201)).body;
      expect(made).toMatchObject({ weightKg: 72.35, bodyFatPct: 18.3, waistCm: 84.2, chestCm: 100, armCm: 30.1 });
      const edited = (await api(u.token).patch(`/api/v1/body-measurements/${made.id}`).send({ hipCm: 95.55, thighCm: 55.04 }).expect(200)).body;
      expect(edited).toMatchObject({ hipCm: 95.6, thighCm: 55, weightKg: 72.35 });
      await api(u.token).post('/api/v1/body-measurements').send({ date: '2026-09-10', waistCm: 84.255 }).expect(400);
      await api(u.token).post('/api/v1/body-measurements').send({ date: '2026-09-10', weightKg: 72.355 }).expect(400);
      // 69.96 % is within the range and is stored rounded to 70.
      expect((await api(u.token).post('/api/v1/body-measurements').send({ date: '2026-09-11', bodyFatPct: 69.96 }).expect(201)).body.bodyFatPct).toBe(70);
    });

    it('PATCH and DELETE take an optional Idempotency-Key and replay with it', async () => {
      const u = await signUp();
      const made = (await api(u.token).post('/api/v1/body-measurements').send({ date: '2026-09-12', weightKg: 70 }).expect(201)).body;
      const url = `/api/v1/body-measurements/${made.id}`;
      await api(u.token).patch(url).send({ weightKg: 70.5 }).expect(200);
      const k = randomUUID();
      await api(u.token).patch(url, k).send({ weightKg: 71 }).expect(200);
      expect((await api(u.token).patch(url, k).send({ weightKg: 71 }).expect(200)).headers['idempotent-replayed']).toBe('true');
      expect((await api(u.token).patch(url, k).send({ weightKg: 72 }).expect(409)).body.code).toBe('IDEMPOTENCY_KEY_REUSED');
      const d = randomUUID();
      await api(u.token).delete(url, d).expect(204);
      expect((await api(u.token).delete(url, d).expect(204)).headers['idempotent-replayed']).toBe('true');
      await api(u.token).delete(url).expect(404);
    });

    it('validates the range', async () => {
      const u = await signUp();
      await api(u.token).get('/api/v1/progress/summary?from=2026-10-01').expect(400);
      await api(u.token).get('/api/v1/progress/summary?from=2026-10-05&to=2026-10-01').expect(400);
      await api(u.token).get('/api/v1/progress/summary?from=2025-01-01&to=2026-10-01').expect(400);
      const year = (await api(u.token).get('/api/v1/progress/summary?from=2025-10-02&to=2026-10-02').expect(200)).body;
      expect(year.points).toHaveLength(366);
    });

    it('counts the streak of consecutive IST days ending today (or yesterday until today ends)', async () => {
      const u = await signUp();
      const today = localDate(new Date(), 'Asia/Kolkata');
      const noonIst = (d: string) => new Date(`${d}T06:30:00.000Z`);
      const y1 = prevDate(today);
      const y2 = prevDate(y1);
      const y4 = prevDate(prevDate(y2));
      expect((await api(u.token).get(`/api/v1/progress/summary?from=${today}&to=${today}`).expect(200)).body.streakDays).toBe(0);
      await workoutAt(u.token, u.id, noonIst(y1));
      await workoutAt(u.token, u.id, noonIst(y2));
      await workoutAt(u.token, u.id, noonIst(y4)); // gap on y3
      expect((await api(u.token).get(`/api/v1/progress/summary?from=${today}&to=${today}`).expect(200)).body.streakDays).toBe(2);
      await workoutAt(u.token, u.id, new Date());
      const s = (await api(u.token).get(`/api/v1/progress/summary?from=${y2}&to=${today}`).expect(200)).body;
      expect(s.streakDays).toBe(3);
      expect(s.points.map((p: { workoutsCompleted: number }) => p.workoutsCompleted)).toEqual([1, 1, 1]);
    });

    it("another user's data never appears", async () => {
      const a = await signUp();
      const b = await signUp();
      await api(a.token).post('/api/v1/body-measurements').send({ date: '2026-10-05', weightKg: 80 }).expect(201);
      const s = (await api(b.token).get('/api/v1/progress/summary?from=2026-10-05&to=2026-10-05').expect(200)).body;
      expect(s.points[0]).toEqual({ date: '2026-10-05', weightKg: null, bodyFatPct: null, waistCm: null, waterMl: null, workoutsCompleted: 0, activeMinutes: 0, energyKcal: null, xpEarned: 0 });
    });
  });
});
