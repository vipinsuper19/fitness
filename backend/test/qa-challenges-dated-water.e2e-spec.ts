/**
 * QA (#109): water challenges and the HYDRATED_7 badge judge each local day against the water
 * goal in force that day (target history), else 2000 ml, over HTTP + Postgres (DB_TESTS=1).
 * Everything goes through the public API, day by day: the clock (Date only) is set to noon IST
 * on each day and the user logs in again, so water is logged on its own day (no backdating) and
 * goals change through PUT /me/profile, exactly as the app does it.
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
import { AchievementsService } from '../src/achievements/achievements.service';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const PASSWORD = 'correct horse battery';
const DATE_ONLY = ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'] as const;

const spec = parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: unknown };
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: 'openapi', components: spec.components });
const validChallenge = ajv.compile({ $ref: 'openapi#/components/schemas/Challenge' });

/** Week of Mon 2026-10-19 .. Sun 2026-10-25 (IST). */
const MON = '2026-10-19';
const TUE = '2026-10-20';
const WED = '2026-10-21';
const THU = '2026-10-22';

describeDb('QA #109: dated water goals in challenges and HYDRATED_7 (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  let engine: AchievementsService;
  const http = () => request(app.getHttpServer());

  /** A user who lives through the days: `on(date)` moves the clock to noon IST and logs in again. */
  const person = async () => {
    const email = `qa-water-${randomUUID()}@example.in`;
    let token = '';
    let id = '';
    const auth = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    const u = {
      email,
      get id() { return id; },
      /** A local date (noon IST) or an exact instant. */
      on: async (date: string) => {
        jest.setSystemTime(new Date(date.includes('T') ? date : `${date}T06:30:00Z`));
        if (!token) {
          const b = (await http().post('/api/v1/auth/register').send({ email, password: PASSWORD }).expect(201)).body;
          id = b.user.id;
          token = b.accessToken;
        } else {
          token = (await http().post('/api/v1/auth/login').send({ email, password: PASSWORD }).expect(200)).body.accessToken;
        }
        return date;
      },
      goal: (ml: number | null) => auth(http().put('/api/v1/me/profile')).set('Idempotency-Key', randomUUID()).send({ dailyWaterTargetMl: ml }).expect(200),
      drink: async (date: string, amountMl: number) => {
        await auth(http().post('/api/v1/water-logs')).set('Idempotency-Key', randomUUID()).send({ date, amountMl }).expect(201);
        await engine.idle();
      },
      join: async (target: number, templateCode = 'WEEKLY_WATER_DAYS', status = 201) =>
        (await auth(http().post('/api/v1/challenges')).set('Idempotency-Key', randomUUID()).send({ templateCode, target }).expect(status)).body,
      challenge: async (cid: string) => {
        const b = (await auth(http().get(`/api/v1/challenges/${cid}`)).expect(200)).body;
        expect(validChallenge(b)).toBe(true);
        return b;
      },
      hydrated: async () => (await auth(http().get('/api/v1/badges')).expect(200)).body.items.find((b: { code: string }) => b.code === 'HYDRATED_7'),
      ledger: async (sourceType: string) =>
        (await auth(http().get('/api/v1/xp/ledger?limit=100')).expect(200)).body.items.filter((i: { sourceType: string }) => i.sourceType === sourceType),
      history: async () => (await auth(http().get('/api/v1/me/target-history')).expect(200)).body.items,
    };
    return u;
  };

  beforeAll(async () => {
    app = await createTestApp({ env: { ACHIEVEMENTS_HOOKS: 'on' } });
    prisma = app.get(PrismaService);
    engine = app.get(AchievementsService);
    await seed(prisma);
  });
  beforeEach(() => jest.useFakeTimers({ now: new Date(`${MON}T06:30:00Z`), doNotFake: [...DATE_ONLY] }));
  afterEach(async () => {
    await engine.idle();
    jest.useRealTimers();
  });
  afterAll(async () => {
    await app.close();
  });

  it('goal raised mid-week: earlier days keep their goal, later days need the new one; waterGoalMl stays the join value', async () => {
    const u = await person();
    await u.on(MON);
    await u.goal(2000);
    const c = await u.join(3);
    expect(c).toMatchObject({ windowStart: MON, waterGoalMl: 2000, progress: 0, status: 'active' });
    await u.drink(MON, 2500);
    await u.on(TUE);
    await u.drink(TUE, 2000); // exactly the goal counts
    await u.on(WED);
    await u.goal(3000);
    await u.drink(WED, 2500); // < 3000
    expect(await u.challenge(c.id)).toMatchObject({ progress: 2, status: 'active', waterGoalMl: 2000 });
    expect((await u.history()).map((h: { effectiveFrom: string; waterMl: number }) => [h.effectiveFrom, h.waterMl])).toEqual([[WED, 3000], [MON, 2000]]);
    await u.drink(WED, 500); // Wed reaches 3000
    const done = await u.challenge(c.id);
    expect(done).toMatchObject({ progress: 3, status: 'completed', waterGoalMl: 2000 });
    expect(done.xpAwarded).toBeGreaterThan(0);
    expect(await u.ledger('challenge')).toEqual([expect.objectContaining({ amount: done.xpAwarded })]);
  });

  it('goal lowered mid-week does not count earlier days retroactively', async () => {
    const u = await person();
    await u.on(MON);
    await u.goal(3000);
    const c = await u.join(3);
    await u.drink(MON, 2500); // < Monday's 3000
    await u.on(TUE);
    await u.goal(1500);
    await u.drink(TUE, 1500);
    expect(await u.challenge(c.id)).toMatchObject({ progress: 1, status: 'active', waterGoalMl: 3000 });
  });

  it('a later goal does not change a day already judged, in either direction', async () => {
    const u = await person();
    await u.on(MON);
    await u.goal(2000);
    const c = await u.join(5);
    await u.drink(MON, 2000);
    await u.on(TUE);
    await u.goal(8000); // Monday stays met
    expect((await u.challenge(c.id)).progress).toBe(1);
    await u.goal(1000); // same-day replace: Tuesday's goal is now 1000, Monday still 2000
    await u.drink(TUE, 1000);
    expect((await u.challenge(c.id)).progress).toBe(2);
  });

  it('no goal ever set, or water goal cleared: the day needs 2000 ml', async () => {
    const u = await person();
    await u.on(MON); // never set a goal
    const c = await u.join(5);
    expect(c.waterGoalMl).toBe(2000);
    await u.drink(MON, 1999);
    expect((await u.challenge(c.id)).progress).toBe(0);
    await u.drink(MON, 1);
    expect((await u.challenge(c.id)).progress).toBe(1);
    await u.on(TUE);
    await u.goal(500);
    await u.drink(TUE, 500);
    expect((await u.challenge(c.id)).progress).toBe(2);
    await u.on(WED);
    await u.goal(null); // cleared: back to 2000
    await u.drink(WED, 1500);
    expect((await u.challenge(c.id)).progress).toBe(2);
    await u.drink(WED, 500);
    expect((await u.challenge(c.id)).progress).toBe(3);
  });

  it("another user's goals never affect my days", async () => {
    const me = await person();
    const other = await person();
    await me.on(MON);
    await me.goal(2000);
    const c = await me.join(3);
    await other.on(MON);
    await other.goal(9000);
    await me.on(MON);
    await me.drink(MON, 2000);
    expect((await me.challenge(c.id)).progress).toBe(1);
  });

  it('HYDRATED_7: goal raised mid-streak, each day judged on its own goal; never revoked; XP once', async () => {
    const u = await person();
    const days = ['2026-10-13', '2026-10-14', '2026-10-15', '2026-10-16', '2026-10-17', '2026-10-18', MON];
    await u.on(days[0]!);
    await u.goal(2000);
    for (const [i, d] of days.entries()) {
      await u.on(d);
      if (i === 3) await u.goal(3000);
      await u.drink(d, 2500); // meets 2000 on days 1-3, short of 3000 on days 4-7
    }
    expect(await u.hydrated()).toMatchObject({ earnedAt: null, progress: { current: 3, target: 7 } });
    for (const d of [TUE, WED, THU, '2026-10-23']) {
      await u.on(d);
      await u.drink(d, 3000); // meets 3000
    }
    // 3 early days at 2500 (< today's 3000) still count: 3 + 4 = 7.
    const b = await u.hydrated();
    expect(b.earnedAt).not.toBeNull();
    expect(b.progress).toBeNull();
    expect(await u.ledger('badge')).toEqual(expect.arrayContaining([expect.objectContaining({ amount: 20, reason: expect.stringContaining('Well hydrated') })]));
    const badgeXp = (await u.ledger('badge')).length;
    await u.on('2026-10-24');
    await u.goal(10000);
    expect((await u.hydrated()).earnedAt).toBe(b.earnedAt);
    expect((await u.ledger('badge')).length).toBe(badgeXp);
  });

  it('a future-dated water log does not count today, even when it meets that day\'s goal', async () => {
    const u = await person();
    await u.on(MON);
    await u.goal(2000);
    const c = await u.join(3);
    await u.drink(THU, 2500); // tomorrow+ in the same week
    expect((await u.challenge(c.id)).progress).toBe(0);
  });

  describe('DW8: IST Monday week boundary', () => {
    const SUN_2330_IST = '2026-10-25T18:00:00Z';
    const MON_0030_IST = '2026-10-26T00:30:00+05:30';
    const SUN_1900_UTC = '2026-10-25T19:00:00Z'; // the same instant as Monday 00:30 IST

    it('Sunday 23:30 IST counts for the old week; Monday 00:30 IST (= Sunday 19:00 UTC) is the new week; UTC date is never used', async () => {
      const u = await person();
      await u.on(SUN_2330_IST);
      await u.goal(2000);
      const a = await u.join(3);
      expect(a).toMatchObject({ windowStart: MON, windowEnd: '2026-10-25' });
      await u.drink('2026-10-25', 2000); // Sunday, today in IST
      await u.drink('2026-10-26', 1000); // Monday is still the future at 23:30 IST
      expect((await u.challenge(a.id)).progress).toBe(1);

      await u.on(MON_0030_IST);
      const b = await u.join(3);
      expect(b).toMatchObject({ windowStart: '2026-10-26', windowEnd: '2026-11-01', progress: 0 });
      await u.drink('2026-10-26', 1000); // Monday: 1000 now + the 1000 logged as future on Sunday night
      expect((await u.challenge(b.id)).progress).toBe(0); // the future-dated 1000 never counts (AB1)
      await u.drink('2026-10-26', 1000);
      expect((await u.challenge(b.id)).progress).toBe(1);
      expect(await u.challenge(a.id)).toMatchObject({ status: 'expired', progress: 1 });

      await u.on(SUN_1900_UTC); // UTC says Sunday 25th, IST says Monday 26th
      await u.drink('2026-10-27', 2000); // Tuesday: future in IST, so no count
      expect((await u.challenge(b.id)).progress).toBe(1);
      expect(await u.challenge(a.id)).toMatchObject({ status: 'expired', progress: 1 });
      // A weekly join at 19:00 UTC Sunday is the IST week starting Monday 26th: already joined.
      expect((await u.join(3, 'WEEKLY_WATER_DAYS', 409)).code).toBe('CHALLENGE_ALREADY_JOINED');
    });
  });

  describe('DW9: weekly challenge XP cap with dated water goals', () => {
    it('cap reached first: the water challenge completes with xpAwarded 0, no ledger row, never paid on re-evaluation', async () => {
      const u = await person();
      const workouts = async (n: number, at: string) => {
        const completedAt = new Date(at);
        for (let i = 0; i < n; i += 1) {
          await prisma.workoutSession.create({ data: { userId: u.id, status: 'completed', startedAt: new Date(completedAt.getTime() - 1_800_000), completedAt, activeSeconds: 1800 } });
        }
      };
      await u.on(`${MON}T04:30:00Z`); // 10:00 IST
      await u.goal(2000);
      const water = await u.join(3);
      const ww = await u.join(2, 'WEEKLY_WORKOUTS'); // 30
      const am = await u.join(60, 'WEEKLY_ACTIVE_MINUTES'); // 30
      const p1 = await u.join(10, 'PERSONAL_WORKOUTS_N'); // 50
      await workouts(10, `${MON}T05:00:00Z`);
      await u.on(`${MON}T05:30:00Z`);
      for (const c of [ww, am, p1]) expect((await u.challenge(c.id)).status).toBe('completed');
      const p2 = await u.join(10, 'PERSONAL_WORKOUTS_N'); // 50, but only 40 left under the cap
      await workouts(10, `${MON}T06:00:00Z`);
      await u.on(`${MON}T06:30:00Z`);
      expect(await u.challenge(p2.id)).toMatchObject({ status: 'completed', xpAwarded: 40 });
      const paid = (await u.ledger('challenge')).reduce((t: number, i: { amount: number }) => t + i.amount, 0);
      expect(paid).toBe(150);

      // Water on dated goals: Mon 2000 (goal 2000), Tue 2500 (goal raised to 2500), Wed 2500.
      await u.drink(MON, 2000);
      await u.on(TUE);
      await u.goal(2500);
      await u.drink(TUE, 2500);
      expect((await u.challenge(water.id)).progress).toBe(2);
      await u.on(WED);
      await u.drink(WED, 2500);
      const done = await u.challenge(water.id);
      expect(done).toMatchObject({ status: 'completed', progress: 3, xpAwarded: 0, waterGoalMl: 2000 });
      const rows = () => prisma.xPTransaction.count({ where: { userId: u.id, sourceType: 'challenge', sourceId: water.id } });
      expect(await rows()).toBe(0);

      // Re-evaluate in every way the API allows: reads, more water, a goal change, the engine directly.
      await u.drink(WED, 500);
      await u.goal(1000);
      await u.hydrated();
      await u.challenge(water.id);
      await engine.evaluate(u.id, new Date());
      await engine.idle();
      expect(await u.challenge(water.id)).toMatchObject({ status: 'completed', xpAwarded: 0, completedAt: done.completedAt });
      expect(await rows()).toBe(0);
      expect((await u.ledger('challenge')).reduce((t: number, i: { amount: number }) => t + i.amount, 0)).toBe(150);
    });
  });
});
