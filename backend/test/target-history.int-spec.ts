/**
 * Dated target history (docs/specs/target-history.md) over HTTP against Postgres (DB_TESTS=1):
 * PUT /me/profile writes (today's row, same-day replace, no-op, new timezone, same transaction),
 * the migration backfill and `targets:backfill` reconcile, per-day targets in /nutrition/summary
 * and /nutrition/daily, GET /me/target-history (+429), export and account deletion.
 * The clock is faked (Date only) and never jumps forward past a token's lifetime; history
 * rows for earlier dates are written directly with Prisma.
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
import { PrismaService } from '../src/prisma/prisma.service';
import * as history from '../src/targets/target-history';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const NOW = new Date('2026-10-29T06:30:00Z'); // 12:00 IST
const DATE_ONLY: Parameters<typeof jest.useFakeTimers>[0] = {
  now: NOW,
  doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'],
};
const PASSWORD = 'correct horse battery';

const spec = parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: unknown };
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: 'openapi', components: spec.components });
const schema = (name: string) => ajv.compile({ $ref: `openapi#/components/schemas/${name}` });
const validHistory = schema('TargetHistory');
const validSummary = schema('NutritionSummary');
const validDaily = schema('DailyNutrition');
const validExport = schema('DataExport');
const valid = (v: ReturnType<typeof schema>, body: unknown) => {
  const ok = v(body);
  expect(v.errors ?? null).toBeNull();
  expect(ok).toBe(true);
};

const BACKFILL_SQL = (() => {
  const sql = readFileSync(join(__dirname, '..', 'prisma', 'migrations', '20261029001200_profile_target_history', 'migration.sql'), 'utf8');
  return sql.slice(sql.indexOf('INSERT INTO'));
})();

type Snap = { kcal?: number | null; waterMl?: number | null; proteinG?: number | null; carbsG?: number | null; fatG?: number | null };
const full = (s: Snap) => ({ kcal: null, waterMl: null, proteinG: null, carbsG: null, fatG: null, ...s });

describeDb('target history (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const as = (token: string) => {
    const auth = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string) => auth(http().post(url)).set('Idempotency-Key', randomUUID()),
      put: (url: string) => auth(http().put(url)),
    };
  };
  const signUp = async () => {
    const b = (await http().post('/api/v1/auth/register').send({ email: `th-${randomUUID()}@example.in`, password: PASSWORD }).expect(201)).body;
    return { token: b.accessToken as string, id: b.user.id as string };
  };
  const put = (token: string, body: object, status = 200) => as(token).put('/api/v1/me/profile').send(body).expect(status);
  const rows = async (userId: string) =>
    (await prisma.profileTargetHistory.findMany({ where: { userId }, orderBy: { effectiveFrom: 'asc' } })).map(history.toEntry);
  const row = (userId: string, date: string, s: Snap) =>
    history.upsertRow(prisma, userId, date, full(s));
  const food = async (token: string, n: { energyKcal: number; proteinG?: number; carbsG?: number; fatG?: number }) =>
    (await as(token).post('/api/v1/foods').send({
      name: `f-${randomUUID().slice(0, 8)}`, servingSize: 1, servingUnit: 'piece', gramsPerServing: 100,
      nutrients: { proteinG: 0, carbsG: 0, fatG: 0, ...n }, isVegetarian: true,
    }).expect(201)).body.id as string;
  const log = (token: string, foodId: string, date: string) =>
    as(token).post('/api/v1/food-logs').send({ foodId, date, mealType: 'lunch', servings: 1 }).expect(201);
  const water = (token: string, date: string, amountMl: number) => as(token).post('/api/v1/water-logs').send({ date, amountMl }).expect(201);
  const summary = async (token: string, qs: string) => {
    const b = (await as(token).get(`/api/v1/nutrition/summary?${qs}`).expect(200)).body;
    valid(validSummary, b);
    return b;
  };
  const daily = async (token: string, date: string) => {
    const b = (await as(token).get(`/api/v1/nutrition/daily?date=${date}`).expect(200)).body;
    valid(validDaily, b);
    return b;
  };
  const getHistory = async (token: string) => {
    const b = (await as(token).get('/api/v1/me/target-history').expect(200)).body;
    valid(validHistory, b);
    return b.items as Array<ReturnType<typeof full> & { effectiveFrom: string }>;
  };

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  beforeEach(() => jest.useFakeTimers(DATE_ONLY));
  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });
  afterAll(async () => {
    await app.close();
  });

  describe('PUT /me/profile writes', () => {
    it('a target change writes today\'s row with all five values; same day replaces; next day adds', async () => {
      const u = await signUp();
      await put(u.token, { dailyWaterTargetMl: 2500, dailyProteinTargetG: 90 });
      await put(u.token, { dailyCalorieTargetKcal: 2000 });
      expect(await rows(u.id)).toEqual([{ effectiveFrom: '2026-10-29', ...full({ kcal: 2000, waterMl: 2500, proteinG: 90 }) }]);
      await put(u.token, { dailyCalorieTargetKcal: 1900, dailyFatTargetG: 60 });
      expect(await rows(u.id)).toEqual([{ effectiveFrom: '2026-10-29', ...full({ kcal: 1900, waterMl: 2500, proteinG: 90, fatG: 60 }) }]);
      // Next local day (the clock moves forward only a day; the token is still valid).
      jest.setSystemTime(new Date('2026-10-30T06:30:00Z'));
      await put(u.token, { dailyCarbsTargetG: 250 });
      expect((await rows(u.id)).map((r) => [r.effectiveFrom, r.kcal, r.carbsG])).toEqual([['2026-10-29', 1900, null], ['2026-10-30', 1900, 250]]);
    });

    it('no target change writes nothing: omitted, same value re-sent, non-target field only', async () => {
      const u = await signUp();
      await put(u.token, { displayName: 'Asha', heightCm: 160 });
      expect(await rows(u.id)).toEqual([]);
      await put(u.token, { dailyCalorieTargetKcal: 2000 });
      const before = await prisma.profileTargetHistory.findMany({ where: { userId: u.id } });
      await put(u.token, { dailyCalorieTargetKcal: 2000 });
      await put(u.token, { weightKg: 70, timezone: 'Asia/Kolkata' });
      await put(u.token, { dailyProteinTargetG: null });
      expect(await prisma.profileTargetHistory.findMany({ where: { userId: u.id } })).toEqual(before);
    });

    it('0 is stored as null; an Idempotency-Key replay writes nothing', async () => {
      const u = await signUp();
      await put(u.token, { dailyProteinTargetG: 0, dailyCarbsTargetG: 0 });
      expect(await rows(u.id)).toEqual([]); // 0 = null = unchanged
      const key = randomUUID();
      await as(u.token).put('/api/v1/me/profile').set('Idempotency-Key', key).send({ dailyCalorieTargetKcal: 2000, dailyProteinTargetG: 0 }).expect(200);
      expect((await rows(u.id))[0]).toMatchObject({ kcal: 2000, proteinG: null });
      await put(u.token, { dailyCalorieTargetKcal: 2100 });
      await as(u.token).put('/api/v1/me/profile').set('Idempotency-Key', key).send({ dailyCalorieTargetKcal: 2000, dailyProteinTargetG: 0 }).expect(200);
      expect((await rows(u.id)).map((r) => r.kcal)).toEqual([2100]);
    });
  });

  describe('timezone (decision 1)', () => {
    it('23:30 UTC on Oct 9 is Oct 10 in Asia/Kolkata', async () => {
      jest.setSystemTime(new Date('2026-10-09T23:30:00Z'));
      const u = await signUp();
      await put(u.token, { dailyCalorieTargetKcal: 2000 });
      expect((await rows(u.id)).map((r) => r.effectiveFrom)).toEqual(['2026-10-10']);
    });

    it('a PUT changing timezone and a target together dates the row in the new zone', async () => {
      jest.setSystemTime(new Date('2026-10-09T20:00:00Z')); // Oct 9 16:00 New York, Oct 10 01:30 Kolkata
      const u = await signUp();
      await put(u.token, { timezone: 'America/New_York' });
      await put(u.token, { timezone: 'Asia/Kolkata', dailyCalorieTargetKcal: 2000 });
      expect((await rows(u.id)).map((r) => r.effectiveFrom)).toEqual(['2026-10-10']);
    });

    it('same transaction: a failed history write rolls back the profile change', async () => {
      const u = await signUp();
      await put(u.token, { dailyCalorieTargetKcal: 2000, timezone: 'Asia/Kolkata' });
      jest.spyOn(history, 'recordTargetChange').mockRejectedValueOnce(new Error('forced'));
      await put(u.token, { dailyCalorieTargetKcal: 1800, timezone: 'America/New_York' }, 500);
      const p = (await as(u.token).get('/api/v1/me/profile').expect(200)).body;
      expect([p.dailyCalorieTargetKcal, p.timezone]).toEqual([2000, 'Asia/Kolkata']);
      expect((await rows(u.id)).map((r) => r.kcal)).toEqual([2000]);
    });
  });

  describe('backfill', () => {
    it('migration backfill: one 1900-01-01 row per profile with targets, none without; re-run is a no-op; past summaries unchanged', async () => {
      const a = await signUp();
      const b = await signUp();
      // Old app: targets on the profile only, no history.
      await prisma.userProfile.update({ where: { userId: a.id }, data: { dailyCalorieTargetKcal: 2000, dailyWaterTargetMl: 2500, dailyProteinTargetG: 0 } });
      await log(a.token, await food(a.token, { energyKcal: 1001 }), '2026-10-05');
      await log(a.token, await food(a.token, { energyKcal: 1000 }), '2026-10-06');
      // Before the migration, the current target applied: (1001 + 1000) / 2 / 2000 = 50.025 -> 50.0.
      await prisma.$executeRawUnsafe(BACKFILL_SQL);
      expect(await rows(a.id)).toEqual([{ effectiveFrom: '1900-01-01', ...full({ kcal: 2000, waterMl: 2500 }) }]);
      expect(await rows(b.id)).toEqual([]);
      const first = await prisma.profileTargetHistory.findMany({ where: { userId: a.id } });
      await prisma.$executeRawUnsafe(BACKFILL_SQL);
      expect(await prisma.profileTargetHistory.findMany({ where: { userId: a.id } })).toEqual(first);
      const s = await summary(a.token, 'from=2026-10-05&to=2026-10-11&bucket=week');
      expect(s.points[0].adherence.kcalPct).toBe(50);
      expect(s.targets).toMatchObject({ kcal: 2000, waterMl: 2500 });
    });

    it('targets:backfill reconcile adds today\'s row once after an old-app PUT; re-run is a no-op', async () => {
      const u = await signUp();
      await put(u.token, { dailyCalorieTargetKcal: 2000 });
      await prisma.profileTargetHistory.updateMany({ where: { userId: u.id }, data: { effectiveFrom: new Date('1900-01-01T00:00:00Z') } });
      // Old app between migrate and deploy: profile changes without history.
      await prisma.userProfile.update({ where: { userId: u.id }, data: { dailyCalorieTargetKcal: 1800 } });
      const fresh = await signUp();
      await prisma.userProfile.update({ where: { userId: fresh.id }, data: { dailyWaterTargetMl: 3000 } });
      const r1 = await history.reconcileAll(prisma, new Date());
      expect(r1.failed).toBe(0);
      expect((await rows(u.id)).map((r) => [r.effectiveFrom, r.kcal])).toEqual([['1900-01-01', 2000], ['2026-10-29', 1800]]);
      expect((await rows(fresh.id)).map((r) => [r.effectiveFrom, r.waterMl])).toEqual([['1900-01-01', 3000]]);
      const r2 = await history.reconcileAll(prisma, new Date());
      expect(r2.written).toBe(0);
      expect(await rows(u.id)).toHaveLength(2);
    });
  });

  describe('reads', () => {
    /** 2000 kcal (water 2000) from Oct 1, then 1800 kcal from Oct 10; 1000 kcal logged Oct 8–11. */
    const changedMidWeek = async () => {
      const u = await signUp();
      await row(u.id, '2026-10-01', { kcal: 2000, waterMl: 2000 });
      await row(u.id, '2026-10-10', { kcal: 1800, waterMl: 2000 });
      await prisma.userProfile.update({ where: { userId: u.id }, data: { dailyCalorieTargetKcal: 1800, dailyWaterTargetMl: 2000 } });
      const f = await food(u.token, { energyKcal: 1000 });
      for (const d of ['2026-10-08', '2026-10-09', '2026-10-10', '2026-10-11']) await log(u.token, f, d);
      return u;
    };

    it('day buckets use each day\'s own target', async () => {
      const u = await changedMidWeek();
      const s = await summary(u.token, 'from=2026-10-09&to=2026-10-10');
      expect(s.points.map((p: { adherence: { kcalPct: number } }) => p.adherence.kcalPct)).toEqual([50, 55.6]);
      expect(s.points.map((p: { targets: { kcal: number } }) => p.targets.kcal)).toEqual([2000, 1800]);
      expect(s.points.map((p: { targetsChanged: boolean }) => p.targetsChanged)).toEqual([false, false]);
    });

    it('a week straddling the change: ratio of sums, targetsChanged, targets of the last day', async () => {
      const u = await changedMidWeek();
      const s = await summary(u.token, 'from=2026-10-05&to=2026-10-11&bucket=week');
      // 4000 / (2000 + 2000 + 1800 + 1800) = 52.63 -> 52.6
      expect(s.points[0]).toMatchObject({ daysLogged: 4, targetsChanged: true, targets: { kcal: 1800 }, adherence: { kcalPct: 52.6 } });
      const before = await summary(u.token, 'from=2026-10-05&to=2026-10-09&bucket=week');
      expect(before.points[0]).toMatchObject({ targetsChanged: false, adherence: { kcalPct: 50 } });
    });

    it('top-level targets are those in effect on `to` (decision 2), not the profile\'s current ones', async () => {
      const u = await changedMidWeek();
      const s = await summary(u.token, 'from=2026-10-05&to=2026-10-09');
      expect(s.targets).toEqual({ kcal: 2000, proteinG: null, carbsG: null, fatG: null, waterMl: 2000 });
      expect((await as(u.token).get('/api/v1/me/profile').expect(200)).body.dailyCalorieTargetKcal).toBe(1800);
      const early = await summary(u.token, 'from=2026-09-01&to=2026-09-30&bucket=month');
      expect(early.targets).toBeNull();
      expect(early.points[0]).toMatchObject({ targets: null, targetsChanged: false });
    });

    it('constant target: identical to avg / target (regression), HALF_UP from unrounded sums', async () => {
      const u = await signUp();
      await row(u.id, '1900-01-01', { kcal: 2000, proteinG: 3 });
      await log(u.token, await food(u.token, { energyKcal: 1001, proteinG: 10.1 }), '2026-10-05');
      await log(u.token, await food(u.token, { energyKcal: 1001, proteinG: 10.2 }), '2026-10-06');
      const s = await summary(u.token, 'from=2026-10-05&to=2026-10-11&bucket=week');
      // 2002 / 4000 = 50.05 -> 50.1; protein 20.3 / 6 = 338.33 -> 338.3 (rounded avg 10.2 would give 340).
      expect(s.points[0].adherence).toEqual({ kcalPct: 50.1, proteinPct: 338.3, carbsPct: null, fatPct: null, waterPct: null });
      expect(s.points[0].targetsChanged).toBe(false);
    });

    it('days before the first target are excluded from pcts but not from averages (decision 3)', async () => {
      const u = await signUp();
      const f = await food(u.token, { energyKcal: 1000 });
      for (const d of ['2026-10-01', '2026-10-02', '2026-10-03']) await log(u.token, f, d);
      await row(u.id, '2026-10-03', { kcal: 2000 });
      const s = await summary(u.token, 'from=2026-10-01&to=2026-10-03&bucket=week');
      expect(s.points[0]).toMatchObject({ daysLogged: 3, avgPerLoggedDay: { energyKcal: 1000 }, targetsChanged: true, adherence: { kcalPct: 50 } });
      const pre = await summary(u.token, 'from=2026-10-01&to=2026-10-02&bucket=week');
      expect(pre.targets).toBeNull();
      expect(pre.points[0]).toMatchObject({ daysLogged: 2, adherence: null, targets: null });
    });

    it('waterPct uses water-logged days with a water target only; null when there is none', async () => {
      const u = await signUp();
      await row(u.id, '2026-10-06', { waterMl: 2000 });
      await water(u.token, '2026-10-05', 3000); // before the water target: not counted
      await water(u.token, '2026-10-06', 1000);
      await water(u.token, '2026-10-07', 1001);
      let s = await summary(u.token, 'from=2026-10-05&to=2026-10-11&bucket=week');
      expect(s.points[0]).toMatchObject({ waterDaysLogged: 3, adherence: { kcalPct: null, waterPct: 50 } }); // 2001 / 4000 = 50.025 -> 50.0
      s = await summary(u.token, 'from=2026-10-05&to=2026-10-05');
      expect(s.points[0].adherence).toBeNull();
    });

    it('/nutrition/daily uses the targets in effect on the date', async () => {
      const u = await changedMidWeek();
      expect(await daily(u.token, '2026-10-09')).toMatchObject({ calorieTargetKcal: 2000, waterTargetMl: 2000 });
      expect(await daily(u.token, '2026-10-10')).toMatchObject({ calorieTargetKcal: 1800, waterTargetMl: 2000 });
      expect(await daily(u.token, '2026-09-30')).toMatchObject({ calorieTargetKcal: null, waterTargetMl: null });
    });
  });

  describe('GET /me/target-history', () => {
    it('newest first; own rows only; 401; no write routes', async () => {
      const u = await signUp();
      const other = await signUp();
      await row(u.id, '1900-01-01', { kcal: 2200 });
      await row(u.id, '2026-10-10', { kcal: 1800 });
      await put(u.token, { dailyCalorieTargetKcal: 2000 });
      await put(other.token, { dailyCalorieTargetKcal: 3000 });
      expect(await getHistory(u.token)).toEqual([
        { effectiveFrom: '2026-10-29', ...full({ kcal: 2000 }) },
        { effectiveFrom: '2026-10-10', ...full({ kcal: 1800 }) },
        { effectiveFrom: '1900-01-01', ...full({ kcal: 2200 }) },
      ]);
      expect(await getHistory((await signUp()).token)).toEqual([]);
      await http().get('/api/v1/me/target-history').expect(401);
      for (const m of ['post', 'patch', 'delete'] as const) {
        const r = await http()[m]('/api/v1/me/target-history').set('Authorization', `Bearer ${u.token}`).send({});
        expect([404, 405]).toContain(r.status);
      }
    });

    it('is rate-limited per user with 429', async () => {
      const limited = await createTestApp({ env: { TARGET_HISTORY_RATE_LIMIT_PER_MINUTE: '2' } });
      try {
        const reg = (await request(limited.getHttpServer()).post('/api/v1/auth/register').send({ email: `th-${randomUUID()}@example.in`, password: PASSWORD }).expect(201)).body;
        const get = () => request(limited.getHttpServer()).get('/api/v1/me/target-history').set('Authorization', `Bearer ${reg.accessToken}`);
        await get().expect(200);
        await get().expect(200);
        const r = await get().expect(429);
        expect(r.body.code).toBe('RATE_LIMITED');
      } finally {
        await limited.close();
      }
    });
  });

  describe('export and deletion', () => {
    it('export includes targetHistory (newest first) and validates; deleting the account removes the rows', async () => {
      const u = await signUp();
      await row(u.id, '1900-01-01', { waterMl: 2500 });
      await put(u.token, { dailyCalorieTargetKcal: 2000 });
      const ex = (await as(u.token).get('/api/v1/me/export').expect(200)).body;
      valid(validExport, ex);
      expect(ex.targetHistory).toEqual([
        { effectiveFrom: '2026-10-29', ...full({ kcal: 2000, waterMl: 2500 }) },
        { effectiveFrom: '1900-01-01', ...full({ waterMl: 2500 }) },
      ]);
      await http().delete('/api/v1/me').set('Authorization', `Bearer ${u.token}`).send({ password: PASSWORD }).expect(204);
      expect(await prisma.profileTargetHistory.count({ where: { userId: u.id } })).toBe(0);
    });
  });
});
