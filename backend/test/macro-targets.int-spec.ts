/**
 * Profile macro targets (docs/specs/macro-targets.md) over HTTP against Postgres (DB_TESTS=1):
 * PUT /me/profile semantics (omit keeps, null clears, 0 stored as null), validation, no
 * derivation from kcal, GET /me/export, and the nutrition summary wiring (independent pcts,
 * HALF_UP from the unrounded average, dated targets for past periods). Clock is faked
 * (Date only) so log dates relative to "today" are stable.
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { seed } from '../prisma/seed';
import { PrismaService } from '../src/prisma/prisma.service';
import { backdateTargets, createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const NOW = new Date('2026-10-29T06:30:00Z'); // 12:00 IST
const DATE_ONLY: Parameters<typeof jest.useFakeTimers>[0] = {
  now: NOW,
  doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'],
};

describeDb('profile macro targets (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const api = (token: string) => {
    const auth = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string) => auth(http().post(url)).set('Idempotency-Key', randomUUID()),
      put: (url: string) => auth(http().put(url)),
    };
  };
  const signUp = async () => {
    const b = (await http().post('/api/v1/auth/register').send({ email: `mt-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body;
    return { token: b.accessToken as string, id: b.user.id as string };
  };
  const put = (token: string, body: object, status = 200) => api(token).put('/api/v1/me/profile').send(body).expect(status);
  const profile = async (token: string) => (await api(token).get('/api/v1/me/profile').expect(200)).body;
  const macros = (p: Record<string, unknown>) => ({ p: p.dailyProteinTargetG, c: p.dailyCarbsTargetG, f: p.dailyFatTargetG });
  const food = async (token: string, n: { proteinG: number; carbsG: number; fatG: number }) =>
    (await api(token).post('/api/v1/foods').send({
      name: `f-${randomUUID().slice(0, 8)}`, servingSize: 1, servingUnit: 'piece', gramsPerServing: 100,
      nutrients: { energyKcal: 100, ...n }, isVegetarian: true,
    }).expect(201)).body.id as string;
  const log = (token: string, foodId: string, date: string) =>
    api(token).post('/api/v1/food-logs').send({ foodId, date, mealType: 'lunch', servings: 1 }).expect(201);
  const summary = async (token: string, qs: string) => (await api(token).get(`/api/v1/nutrition/summary?${qs}`).expect(200)).body;

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  beforeEach(() => jest.useFakeTimers(DATE_ONLY));
  afterEach(() => jest.useRealTimers());
  afterAll(async () => {
    await app.close();
  });

  it('new profiles read null; PUT sets, omitted fields keep, explicit null clears', async () => {
    const u = await signUp();
    expect(macros(await profile(u.token))).toEqual({ p: null, c: null, f: null });
    const r = await put(u.token, { dailyProteinTargetG: 120, dailyCarbsTargetG: 250, dailyFatTargetG: 70 });
    expect(macros(r.body)).toEqual({ p: 120, c: 250, f: 70 });
    await put(u.token, { dailyCarbsTargetG: 200 });
    expect(macros(await profile(u.token))).toEqual({ p: 120, c: 200, f: 70 });
    await put(u.token, { dailyFatTargetG: null });
    expect(macros(await profile(u.token))).toEqual({ p: 120, c: 200, f: null });
  });

  it('0 is accepted but stored and returned as null (PUT response, GET, export)', async () => {
    const u = await signUp();
    await put(u.token, { dailyProteinTargetG: 120 });
    const r = await put(u.token, { dailyProteinTargetG: 0, dailyCarbsTargetG: 0 });
    expect(macros(r.body)).toEqual({ p: null, c: null, f: null });
    expect(macros(await profile(u.token))).toEqual({ p: null, c: null, f: null });
    const row = await prisma.userProfile.findUniqueOrThrow({ where: { userId: u.id } });
    expect([row.dailyProteinTargetG, row.dailyCarbsTargetG]).toEqual([null, null]);
    const exp = (await api(u.token).get('/api/v1/me/export').expect(200)).body;
    expect(macros(exp.profile)).toEqual({ p: null, c: null, f: null });
  });

  it('validation: -1, 1001, 12.5 and a string are 400 VALIDATION_FAILED; 0 and 1000 accepted', async () => {
    const u = await signUp();
    for (const bad of [-1, 1001, 12.5, '50']) {
      const r = await put(u.token, { dailyFatTargetG: bad }, 400);
      expect(r.body.code).toBe('VALIDATION_FAILED');
    }
    await put(u.token, { dailyFatTargetG: 1000 });
    await put(u.token, { dailyFatTargetG: 0 });
  });

  it('no derivation: setting the calorie target leaves macro targets null', async () => {
    const u = await signUp();
    await put(u.token, { dailyCalorieTargetKcal: 2200 });
    expect(macros(await profile(u.token))).toEqual({ p: null, c: null, f: null });
  });

  it('export includes the fields', async () => {
    const u = await signUp();
    await put(u.token, { dailyProteinTargetG: 90, dailyFatTargetG: 60 });
    const exp = (await api(u.token).get('/api/v1/me/export').expect(200)).body;
    expect(macros(exp.profile)).toEqual({ p: 90, c: null, f: 60 });
  });

  it('summary: targets echoed, independent pcts, HALF_UP from the unrounded average, macros-only adherence', async () => {
    const u = await signUp();
    // Two logged days: protein 10.1 + 10.2 -> avg 10.15 (displays 10.2).
    await log(u.token, await food(u.token, { proteinG: 10.1, carbsG: 20, fatG: 5 }), '2026-10-27');
    await log(u.token, await food(u.token, { proteinG: 10.2, carbsG: 20, fatG: 5 }), '2026-10-28');
    // Only protein set; no kcal or water target.
    await put(u.token, { dailyProteinTargetG: 200 });
    await backdateTargets(prisma, u.id);
    let s = await summary(u.token, 'from=2026-10-27&to=2026-10-28&bucket=week');
    expect(s.targets).toEqual({ kcal: null, proteinG: 200, carbsG: null, fatG: null, waterMl: null });
    // 10.15/200*100 = 5.075 -> 5.1 HALF_UP (the rounded 10.2 avg would give exactly 5.1 too; see next case).
    expect(s.points[0].adherence).toEqual({ kcalPct: null, proteinPct: 5.1, carbsPct: null, fatPct: null, waterPct: null });
    // Unrounded-average case: target 3 -> 10.15/3*100 = 338.333.. -> 338.3; rounded avg 10.2 would give 340.0.
    await put(u.token, { dailyProteinTargetG: 3, dailyFatTargetG: 8 });
    await backdateTargets(prisma, u.id);
    s = await summary(u.token, 'from=2026-10-27&to=2026-10-28&bucket=week');
    expect(s.points[0].adherence).toMatchObject({ proteinPct: 338.3, carbsPct: null, fatPct: 62.5 });
  });

  it('summary: PUT 0 -> target and pct null; no food logged -> macro pcts null', async () => {
    const u = await signUp();
    await log(u.token, await food(u.token, { proteinG: 50, carbsG: 100, fatG: 20 }), '2026-10-28');
    await put(u.token, { dailyProteinTargetG: 0, dailyCarbsTargetG: 200 });
    await backdateTargets(prisma, u.id);
    let s = await summary(u.token, 'from=2026-10-28&to=2026-10-28');
    expect(s.targets).toMatchObject({ proteinG: null, carbsG: 200 });
    expect(s.points[0].adherence).toMatchObject({ proteinPct: null, carbsPct: 50 });
    s = await summary(u.token, 'from=2026-10-26&to=2026-10-26');
    expect(s.points[0].adherence).toBeNull();
  });

  it('targets changed today do not change a past period (dated history)', async () => {
    const u = await signUp();
    await log(u.token, await food(u.token, { proteinG: 40, carbsG: 0, fatG: 0 }), '2026-10-20');
    await put(u.token, { dailyProteinTargetG: 80 }); // "today" = 2026-10-29
    const s = await summary(u.token, 'from=2026-10-19&to=2026-10-25&bucket=week');
    expect(s.targets).toBeNull();
    expect(s.points[0].adherence).toBeNull();
    const today = await summary(u.token, 'from=2026-10-29&to=2026-10-29');
    expect(today.targets).toMatchObject({ proteinG: 80 });
  });

  it('auth guard', async () => {
    await http().put('/api/v1/me/profile').send({ dailyProteinTargetG: 100 }).expect(401);
  });
});
