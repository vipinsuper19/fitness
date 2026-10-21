/**
 * GET /nutrition/summary (docs/specs/nutrition-summary.md) over HTTP against Postgres
 * (DB_TESTS=1): day/week/month periods clipped to from/to, null vs 0, averages per
 * logged day, HALF_UP, targets and adherence null rules, water averages, soft-deleted
 * foods, deleted logs, ownership, range validation and the auth guard.
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { seed } from '../prisma/seed';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

describeDb('nutrition summary (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;

  const http = () => request(app.getHttpServer());
  const api = (token: string) => {
    const auth = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string) => auth(http().post(url)).set('Idempotency-Key', randomUUID()),
      put: (url: string) => auth(http().put(url)),
      delete: (url: string) => auth(http().delete(url)),
    };
  };
  const signUp = async () => {
    const b = (await http().post('/api/v1/auth/register').send({ email: `ns-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body;
    return { token: b.accessToken as string, id: b.user.id as string };
  };
  /** A custom food whose one serving is exactly `kcal` (and macros), so totals are predictable. */
  const food = async (token: string, kcal: number, extra: object = {}) =>
    (await api(token).post('/api/v1/foods').send({
      name: `f-${randomUUID().slice(0, 8)}`, servingSize: 1, servingUnit: 'piece', gramsPerServing: 100,
      nutrients: { energyKcal: kcal, proteinG: 10, carbsG: 20, fatG: 5, ...extra }, isVegetarian: true,
    }).expect(201)).body.id as string;
  const log = async (token: string, foodId: string, date: string, servings = 1) =>
    (await api(token).post('/api/v1/food-logs').send({ foodId, date, mealType: 'lunch', servings }).expect(201)).body.id as string;
  const water = (token: string, date: string, amountMl: number) => api(token).post('/api/v1/water-logs').send({ date, amountMl }).expect(201);
  const summary = async (token: string, qs: string) => (await api(token).get(`/api/v1/nutrition/summary?${qs}`).expect(200)).body;

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  it('day bucket: one point per day, null (never 0) for empty days, default bucket day', async () => {
    const u = await signUp();
    const f = await food(u.token, 100);
    await log(u.token, f, '2026-10-02');
    const s = await summary(u.token, 'from=2026-10-01&to=2026-10-03');
    expect(s).toMatchObject({ from: '2026-10-01', to: '2026-10-03', bucket: 'day', timezone: 'Asia/Kolkata', targets: null });
    expect(s.points.map((p: { period: string }) => p.period)).toEqual(['2026-10-01', '2026-10-02', '2026-10-03']);
    expect(s.points[0]).toMatchObject({ daysInPeriod: 1, daysLogged: 0, totals: null, avgPerLoggedDay: null, waterMl: null, waterDaysLogged: 0, avgWaterMlPerLoggedDay: null, adherence: null, entryCount: 0, entriesMissing: { fiberG: 0, sugarG: 0, sodiumMg: 0 } });
    expect(s.points[1]).toMatchObject({ daysLogged: 1, entryCount: 1, totals: { energyKcal: 100, proteinG: 10, fiberG: null }, entriesMissing: { fiberG: 1 } });
  });

  it('week bucket: Monday weeks, clipped at both edges (range starting on a Wednesday); IST Sunday/Monday dates split', async () => {
    const u = await signUp();
    const f = await food(u.token, 100);
    await log(u.token, f, '2026-10-18'); // Sunday (local date): week of Mon 12 Oct
    await log(u.token, f, '2026-10-19'); // Monday: next week
    await log(u.token, f, '2026-10-19');
    const s = await summary(u.token, 'from=2026-10-14&to=2026-10-27&bucket=week'); // Wed .. Tue
    expect(s.points.map((p: { period: string; daysInPeriod: number }) => [p.period, p.daysInPeriod])).toEqual([
      ['2026-10-14', 5], ['2026-10-19', 7], ['2026-10-26', 2],
    ]);
    expect(s.points[0]).toMatchObject({ daysLogged: 1, entryCount: 1, totals: { energyKcal: 100 } });
    expect(s.points[1]).toMatchObject({ daysLogged: 1, entryCount: 2, totals: { energyKcal: 200 }, avgPerLoggedDay: { energyKcal: 200 } });
    expect(s.points[2].totals).toBeNull();
  });

  it('month bucket: calendar months, month boundary, clipped edges, empty months returned', async () => {
    const u = await signUp();
    const f = await food(u.token, 100);
    await log(u.token, f, '2026-08-31');
    await log(u.token, f, '2026-09-01');
    const s = await summary(u.token, 'from=2026-08-15&to=2026-10-10&bucket=month');
    expect(s.points.map((p: { period: string; daysInPeriod: number }) => [p.period, p.daysInPeriod])).toEqual([
      ['2026-08-15', 17], ['2026-09-01', 30], ['2026-10-01', 10],
    ]);
    expect(s.points[0].totals.energyKcal).toBe(100);
    expect(s.points[1].totals.energyKcal).toBe(100);
    expect(s.points[2]).toMatchObject({ totals: null, daysLogged: 0 });
  });

  it('avgPerLoggedDay divides by daysLogged and rounds once, HALF_UP at .x5', async () => {
    const u = await signUp();
    const a = await food(u.token, 100.1);
    const b = await food(u.token, 100.2);
    await log(u.token, a, '2026-10-05');
    await log(u.token, b, '2026-10-07');
    const s = await summary(u.token, 'from=2026-10-05&to=2026-10-11&bucket=week');
    expect(s.points[0]).toMatchObject({ daysInPeriod: 7, daysLogged: 2, totals: { energyKcal: 200.3 } });
    expect(s.points[0].avgPerLoggedDay.energyKcal).toBe(100.2); // 100.15 -> 100.2 (float rounding gives 100.1)
  });

  it('targets and adherence: current targets, null rules, 0 counted as null', async () => {
    const u = await signUp();
    const f = await food(u.token, 1000);
    await log(u.token, f, '2026-10-05');
    await api(u.token).put('/api/v1/me/profile').send({ dailyCalorieTargetKcal: 2000, dailyWaterTargetMl: 2000 }).expect(200);
    await water(u.token, '2026-10-05', 1000);
    await water(u.token, '2026-10-06', 1001);
    let s = await summary(u.token, 'from=2026-10-05&to=2026-10-11&bucket=week');
    expect(s.targets).toEqual({ kcal: 2000, proteinG: null, carbsG: null, fatG: null, waterMl: 2000 });
    expect(s.points[0]).toMatchObject({ waterMl: 2001, waterDaysLogged: 2, avgWaterMlPerLoggedDay: 1001 }); // 1000.5 HALF_UP
    expect(s.points[0].adherence).toEqual({ kcalPct: 50, proteinPct: null, carbsPct: null, fatPct: null, waterPct: 50 }); // 1000.5/2000 = 50.025 -> 50.0

    // Water only (no food): kcalPct null, waterPct set, adherence present.
    s = await summary(u.token, 'from=2026-10-06&to=2026-10-06');
    expect(s.points[0]).toMatchObject({ totals: null, adherence: { kcalPct: null, waterPct: 50.1 } });
    // Food only: waterPct null.
    await log(u.token, f, '2026-10-08');
    s = await summary(u.token, 'from=2026-10-08&to=2026-10-08');
    expect(s.points[0].adherence).toMatchObject({ kcalPct: 50, waterPct: null });

    // A target of 0 counts as null; with no targets at all, targets and adherence are null.
    await prisma.userProfile.update({ where: { userId: u.id }, data: { dailyCalorieTargetKcal: 0, dailyWaterTargetMl: 0 } });
    s = await summary(u.token, 'from=2026-10-05&to=2026-10-11&bucket=week');
    expect(s.targets).toBeNull();
    expect(s.points[0].adherence).toBeNull();
    await prisma.userProfile.update({ where: { userId: u.id }, data: { dailyCalorieTargetKcal: 0, dailyWaterTargetMl: 2000 } });
    s = await summary(u.token, 'from=2026-10-05&to=2026-10-11&bucket=week');
    expect(s.targets).toMatchObject({ kcal: null, waterMl: 2000 });
    expect(s.points[0].adherence).toMatchObject({ kcalPct: null, waterPct: 50 });
  });

  it('a soft-deleted food still counts; a deleted log does not; only the caller\'s data', async () => {
    const u = await signUp();
    const other = await signUp();
    const f = await food(u.token, 300);
    await log(u.token, f, '2026-10-05');
    const gone = await log(u.token, f, '2026-10-05');
    await api(u.token).delete(`/api/v1/food-logs/${gone}`).expect(204);
    await api(u.token).delete(`/api/v1/foods/${f}`).expect(204);
    const o = await food(other.token, 999);
    await log(other.token, o, '2026-10-05');
    await water(other.token, '2026-10-05', 500);
    const s = await summary(u.token, 'from=2026-10-05&to=2026-10-05');
    expect(s.points[0]).toMatchObject({ entryCount: 1, totals: { energyKcal: 300 }, waterMl: null });
  });

  it('optional nutrients: partial totals with entriesMissing, null when every entry lacks one', async () => {
    const u = await signUp();
    const withFiber = await food(u.token, 100, { fiberG: 2.5 });
    const without = await food(u.token, 100);
    await log(u.token, withFiber, '2026-10-05');
    await log(u.token, without, '2026-10-05');
    const s = await summary(u.token, 'from=2026-10-05&to=2026-10-05');
    expect(s.points[0]).toMatchObject({ entryCount: 2, totals: { fiberG: 2.5, sugarG: null }, entriesMissing: { fiberG: 1, sugarG: 2, sodiumMg: 2 } });
  });

  it('validates the range like /progress/summary, and the bucket', async () => {
    const u = await signUp();
    await api(u.token).get('/api/v1/nutrition/summary?from=2026-10-05&to=2026-10-04').expect(400);
    await api(u.token).get('/api/v1/nutrition/summary?from=2025-01-01&to=2026-01-02').expect(400); // 367 days
    await api(u.token).get('/api/v1/nutrition/summary?from=2025-01-01&to=2026-01-01').expect(200); // 366 days
    await api(u.token).get('/api/v1/nutrition/summary?from=2026-02-30&to=2026-03-01').expect(400);
    await api(u.token).get('/api/v1/nutrition/summary?from=1899-12-31&to=1900-01-01').expect(400);
    await api(u.token).get('/api/v1/nutrition/summary?to=2026-10-01').expect(400);
    await api(u.token).get('/api/v1/nutrition/summary?from=2026-10-01&to=2026-10-02&bucket=year').expect(400);
  });

  it('requires sign-in', async () => {
    await http().get('/api/v1/nutrition/summary?from=2026-10-01&to=2026-10-02').expect(401);
  });
});
