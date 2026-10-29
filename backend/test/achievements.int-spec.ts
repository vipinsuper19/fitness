/**
 * Badges & challenges engine against Postgres (DB_TESTS=1): idempotent awards,
 * concurrency, the weekly XP cap, expiry, never-revoke, backfill and the write hooks.
 */
import { randomUUID } from 'node:crypto';

import { Logger } from '@nestjs/common';
import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { CATALOG_VERSION } from '../src/achievements/achievements.rules';
import { AchievementsService } from '../src/achievements/achievements.service';
import { backfillAll } from '../src/achievements/backfill';
import { localDate, startOfLocalDay } from '../src/common/time';
import { PrismaService } from '../src/prisma/prisma.service';
import { NO_TARGETS, upsertRow } from '../src/targets/target-history';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

const tz = 'Asia/Kolkata';
const NOW = new Date('2026-10-21T06:30:00Z'); // Wed 12:00 IST; week starts Mon 2026-10-19
const at = (date: string, hour = 12) => new Date(startOfLocalDay(date, tz).getTime() + hour * 3_600_000);
const d = (s: string) => new Date(`${s}T00:00:00Z`);
const days = (from: string, n: number) => Array.from({ length: n }, (_, i) => new Date(Date.parse(`${from}T00:00:00Z`) + i * 86_400_000).toISOString().slice(0, 10));

describeDb('badges & challenges engine (Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  let engine: AchievementsService;

  beforeAll(async () => {
    app = await createTestApp({ env: { ACHIEVEMENTS_HOOKS: 'on' } });
    prisma = app.get(PrismaService);
    engine = app.get(AchievementsService);
  });
  afterAll(async () => {
    await engine.idle();
    await app.close();
  });

  async function user(profile: { dailyWaterTargetMl?: number } = {}) {
    const u = await prisma.user.create({ data: { email: `ach-${randomUUID()}@test.local`, passwordHash: 'x' } });
    await prisma.userProfile.create({ data: { userId: u.id, timezone: tz, ...profile } });
    return u.id;
  }
  const workout = (userId: string, completedAt: Date, activeSeconds = 1800) =>
    prisma.workoutSession.create({ data: { userId, status: 'completed', startedAt: new Date(completedAt.getTime() - activeSeconds * 1000), completedAt, activeSeconds } });
  const ledger = (userId: string, sourceType: 'badge' | 'challenge') => prisma.xPTransaction.findMany({ where: { userId, sourceType } });
  const challenge = (userId: string, over: Record<string, unknown>) =>
    prisma.userChallenge.create({
      data: { userId, templateCode: 'WEEKLY_WORKOUTS', type: 'weekly', target: 2, windowStart: d('2026-10-19'), windowEnd: d('2026-10-25'), joinDate: d('2026-10-19'), joinedAt: at('2026-10-19', 8), timezone: tz, ...over } as never,
    });

  describe('badges', () => {
    it('awards a badge and its bonus XP exactly once, under concurrent and repeated evaluation', async () => {
      const u = await user();
      await workout(u, at('2026-10-20'));
      const results = await Promise.all(Array.from({ length: 6 }, () => engine.evaluate(u, NOW)));
      expect(results.flatMap((r) => r.badgesAwarded)).toEqual(['FIRST_WORKOUT']);
      expect((await engine.evaluate(u, NOW)).badgesAwarded).toEqual([]);
      expect(await prisma.userBadge.count({ where: { userId: u } })).toBe(1);
      const xp = await ledger(u, 'badge');
      expect(xp).toHaveLength(1);
      expect(xp[0]).toMatchObject({ amount: 10, reason: 'Badge: First workout' });
    });

    it('never revokes: deleting the data keeps the badge and its XP', async () => {
      const u = await user();
      const s = await workout(u, at('2026-10-20'));
      await engine.evaluate(u, NOW);
      await prisma.workoutSession.delete({ where: { id: s.id } });
      await engine.evaluate(u, NOW);
      expect(await prisma.userBadge.count({ where: { userId: u } })).toBe(1);
      expect(await ledger(u, 'badge')).toHaveLength(1);
    });

    it('applies the backdating rule to client-dated entries', async () => {
      const u = await user();
      await prisma.bodyMeasurement.create({ data: { userId: u, date: d('2026-10-10'), weightKg: 70, createdAt: at('2026-10-15') } }); // late
      expect((await engine.evaluate(u, NOW)).badgesAwarded).toEqual([]);
      await prisma.bodyMeasurement.create({ data: { userId: u, date: d('2026-10-19'), weightKg: 70, createdAt: at('2026-10-20', 23) } }); // next day: counts
      expect((await engine.evaluate(u, NOW)).badgesAwarded).toEqual(['FIRST_MEASUREMENT']);
    });

    it('an edit that moves the date earlier stops the entry counting; awarded badges stay', async () => {
      const u = await user();
      // Water goal met on 7 days (2026-10-14..20), each entry logged on its own day.
      const logs = [];
      for (const day of days('2026-10-14', 7)) logs.push(await prisma.waterLog.create({ data: { userId: u, date: d(day), amountMl: 2500, createdAt: at(day, 20) } }));
      expect((await engine.evaluate(u, NOW)).badgesAwarded).toContain('HYDRATED_7');
      // Moving an entry's date 3 days back (still created 2026-10-20) takes it out of the window...
      await prisma.waterLog.update({ where: { id: logs[6]!.id }, data: { date: d('2026-10-17') } });
      const facts = await engine.facts(u, NOW);
      expect(facts.waterByDate.has('2026-10-20')).toBe(false);
      expect(facts.waterByDate.get('2026-10-17')).toBe(2500); // only the original 10-17 entry
      // ...and a new entry dated two days before it was created never counts.
      await prisma.waterLog.create({ data: { userId: u, date: d('2026-10-19'), amountMl: 5000, createdAt: at('2026-10-21', 10) } });
      expect((await engine.facts(u, NOW)).waterByDate.get('2026-10-19')).toBe(2500);
      // Nothing is revoked.
      await engine.evaluate(u, NOW);
      expect(await prisma.userBadge.count({ where: { userId: u, badgeCode: 'HYDRATED_7' } })).toBe(1);
      expect(await ledger(u, 'badge')).toHaveLength(1);
    });

    it('future-dated entries never count, even once their days arrive', async () => {
      const u = await user();
      const c = await challenge(u, { templateCode: 'WEEKLY_WATER_DAYS', target: 3, windowStart: d('2026-10-26'), windowEnd: d('2026-11-01'), joinDate: d('2026-10-26'), joinedAt: at('2026-10-26', 1) });
      // Logged today (2026-10-21) for each of the next 7 days.
      for (const day of days('2026-10-22', 7)) await prisma.waterLog.create({ data: { userId: u, date: d(day), amountMl: 3000, createdAt: at('2026-10-21', 9) } });
      const later = at('2026-10-30', 12);
      expect((await engine.facts(u, later)).waterByDate.size).toBe(0);
      const r = await engine.evaluate(u, later);
      expect(r.badgesAwarded).not.toContain('HYDRATED_7');
      expect(r.challengesCompleted).toEqual([]);
      expect(await prisma.userChallenge.findUniqueOrThrow({ where: { id: c.id } })).toMatchObject({ status: 'active', progress: 0 });
    });

    it('uses the dated water goal (target history), else 2000 ml', async () => {
      const withGoal = await user({ dailyWaterTargetMl: 3000 });
      await upsertRow(prisma, withGoal, '1900-01-01', { ...NO_TARGETS, waterMl: 3000 });
      const noGoal = await user();
      for (const u of [withGoal, noGoal]) {
        for (const day of days('2026-10-13', 7)) await prisma.waterLog.create({ data: { userId: u, date: d(day), amountMl: 2500, createdAt: at(day, 20) } });
      }
      expect((await engine.evaluate(withGoal, NOW)).badgesAwarded).not.toContain('HYDRATED_7');
      expect((await engine.evaluate(noGoal, NOW)).badgesAwarded).toContain('HYDRATED_7');
    });

    it('LEVEL_5 ignores bonus XP and awards no XP row', async () => {
      const u = await user();
      const s = await workout(u, at('2026-10-20'));
      await prisma.xPTransaction.create({ data: { userId: u, sourceType: 'workout_session', sourceId: s.id, amount: 1000, reason: 'test' } });
      const r = await engine.evaluate(u, NOW);
      expect(r.badgesAwarded).toEqual(expect.arrayContaining(['FIRST_WORKOUT', 'LEVEL_5']));
      expect((await ledger(u, 'badge')).map((x) => x.amount)).toEqual([10]);
    });
  });

  describe('challenges', () => {
    it('completes a weekly challenge once with bonus XP, even when evaluated concurrently', async () => {
      const u = await user();
      const c = await challenge(u, {});
      await workout(u, at('2026-10-19'));
      expect((await engine.evaluate(u, NOW)).challengesCompleted).toEqual([]);
      expect((await prisma.userChallenge.findUniqueOrThrow({ where: { id: c.id } })).progress).toBe(1);
      await workout(u, at('2026-10-20'));
      const rs = await Promise.all(Array.from({ length: 5 }, () => engine.evaluate(u, NOW)));
      expect(rs.flatMap((r) => r.challengesCompleted)).toEqual([{ id: c.id, xpAwarded: 30 }]);
      expect(await prisma.userChallenge.findUniqueOrThrow({ where: { id: c.id } })).toMatchObject({ status: 'completed', progress: 2, xpAwarded: 30, completedAt: NOW });
      expect((await ledger(u, 'challenge')).map((x) => [x.sourceId, x.amount])).toEqual([[c.id, 30]]);
    });

    it('caps challenge XP at 150 per Monday-start week: partial, then 0 with no ledger row', async () => {
      const u = await user();
      // 130 XP already awarded this week (completed Mon 2026-10-19 IST).
      await challenge(u, { templateCode: 'PERSONAL_WORKOUTS_N', type: 'personal', status: 'completed', target: 10, progress: 10, xpAwarded: 130, completedAt: at('2026-10-19', 1), windowEnd: null });
      // Last week's award does not count (Sun 23:00 IST).
      await challenge(u, { templateCode: 'WEEKLY_FOOD_LOG_DAYS', windowStart: d('2026-10-12'), windowEnd: d('2026-10-18'), status: 'completed', target: 3, progress: 3, xpAwarded: 150, completedAt: at('2026-10-18', 23) });
      const a = await challenge(u, {});
      const b = await challenge(u, { templateCode: 'WEEKLY_ACTIVE_MINUTES', target: 60, joinedAt: at('2026-10-19', 9) }); // joined later: evaluated (and capped) second
      await workout(u, at('2026-10-19'));
      await workout(u, at('2026-10-20'));
      const r = await engine.evaluate(u, NOW);
      expect(r.challengesCompleted).toEqual([{ id: a.id, xpAwarded: 20 }, { id: b.id, xpAwarded: 0 }]);
      expect((await ledger(u, 'challenge')).map((x) => [x.sourceId, x.amount])).toEqual([[a.id, 20]]);
      expect((await prisma.userChallenge.findUniqueOrThrow({ where: { id: b.id } })).status).toBe('completed');
      await engine.evaluate(u, NOW);
      expect(await ledger(u, 'challenge')).toHaveLength(1);
    });

    it('streak challenge: an existing streak cannot complete it, and re-joining cannot farm it', async () => {
      const u = await user();
      for (const day of days('2026-10-08', 14)) await workout(u, at(day)); // 14-day streak ending today
      const tpl = { templateCode: 'PERSONAL_STREAK_N', type: 'personal', target: 7, windowStart: d('2026-10-21'), windowEnd: null, joinDate: d('2026-10-21'), joinedAt: at('2026-10-21', 9) };
      const first = await challenge(u, tpl);
      expect((await engine.evaluate(u, NOW)).challengesCompleted).toEqual([]);
      expect(await prisma.userChallenge.findUniqueOrThrow({ where: { id: first.id } })).toMatchObject({ status: 'active', progress: 1 });

      // Joined 6 days ago: 7 streak days on/after join -> completes once.
      await prisma.userChallenge.update({ where: { id: first.id }, data: { windowStart: d('2026-10-15'), joinDate: d('2026-10-15') } });
      expect((await engine.evaluate(u, NOW)).challengesCompleted.map((c) => c.id)).toEqual([first.id]);
      // Re-join the same day: counting restarts from the new join date.
      const again = await challenge(u, { ...tpl, joinedAt: at('2026-10-21', 11) });
      expect((await engine.evaluate(u, NOW)).challengesCompleted).toEqual([]);
      expect(await prisma.userChallenge.findUniqueOrThrow({ where: { id: again.id } })).toMatchObject({ status: 'active', progress: 1 });
      expect(await ledger(u, 'challenge')).toHaveLength(1);
    });

    it('expires lazily when the window ended unmet; a null deadline never expires', async () => {
      const u = await user();
      const old = await challenge(u, { windowStart: d('2026-10-12'), windowEnd: d('2026-10-18'), joinDate: d('2026-10-12'), target: 3 });
      const open = await challenge(u, { templateCode: 'PERSONAL_STREAK_N', type: 'personal', windowStart: d('2026-01-01'), windowEnd: null, joinDate: d('2026-01-01'), target: 30 });
      await workout(u, at('2026-10-13'));
      await workout(u, at('2026-10-20')); // outside the old window
      const r = await engine.evaluate(u, new Date('2099-06-01T06:30:00Z'));
      expect(r.challengesExpired).toEqual([old.id]);
      expect(await prisma.userChallenge.findUniqueOrThrow({ where: { id: old.id } })).toMatchObject({ status: 'expired', progress: 1, xpAwarded: 0 });
      expect((await prisma.userChallenge.findUniqueOrThrow({ where: { id: open.id } })).status).toBe('active');
    });

    it('never revokes a completed challenge; leaves left ones alone', async () => {
      const u = await user();
      const c = await challenge(u, {});
      const left = await challenge(u, { templateCode: 'WEEKLY_ACTIVE_MINUTES', target: 30, status: 'left', leftAt: at('2026-10-19', 9) });
      const s1 = await workout(u, at('2026-10-19'));
      await workout(u, at('2026-10-20'));
      await engine.evaluate(u, NOW);
      await prisma.workoutSession.delete({ where: { id: s1.id } });
      await engine.evaluate(u, NOW);
      expect(await prisma.userChallenge.findUniqueOrThrow({ where: { id: c.id } })).toMatchObject({ status: 'completed', xpAwarded: 30 });
      expect(await prisma.userChallenge.findUniqueOrThrow({ where: { id: left.id } })).toMatchObject({ status: 'left', progress: 0 });
    });
  });

  describe('backfill', () => {
    it('ensureBackfilled runs once per catalog version', async () => {
      const u = await user();
      await workout(u, at('2026-10-20'));
      expect(await engine.ensureBackfilled(u, NOW)).toBe(true);
      expect((await prisma.userProfile.findUniqueOrThrow({ where: { userId: u } })).badgesEvaluatedVersion).toBe(CATALOG_VERSION);
      expect(await engine.ensureBackfilled(u, NOW)).toBe(false);
      expect(await prisma.userBadge.count({ where: { userId: u } })).toBe(1);
    });

    it('the backfill script is idempotent', async () => {
      const u = await user();
      await workout(u, at('2026-10-20'));
      const first = await backfillAll(prisma);
      expect(first.failed).toBe(0);
      expect(await prisma.userBadge.count({ where: { userId: u } })).toBeGreaterThanOrEqual(1);
      const badges = await prisma.userBadge.count();
      const xp = await prisma.xPTransaction.count();
      await backfillAll(prisma);
      expect(await prisma.userBadge.count()).toBe(badges);
      expect(await prisma.xPTransaction.count()).toBe(xp);
    });
  });

  describe('write hooks', () => {
    it('are off when ACHIEVEMENTS_HOOKS=off', () => {
      const off = new AchievementsService(prisma, { ACHIEVEMENTS_HOOKS: 'off' });
      const ev = jest.spyOn(off, 'evaluate');
      off.afterWrite(randomUUID());
      expect(ev).not.toHaveBeenCalled();
    });

    const http = () => request(app.getHttpServer());
    const signUp = async () => {
      const res = await http().post('/api/v1/auth/register').send({ email: `ach-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201);
      return { token: res.body.accessToken as string, id: res.body.user.id as string };
    };

    it('evaluates after a committed write, in the background', async () => {
      const { token, id } = await signUp();
      const today = localDate(new Date(), tz);
      await http().post('/api/v1/body-measurements').set('Authorization', `Bearer ${token}`).set('Idempotency-Key', randomUUID()).send({ date: today, weightKg: 70 }).expect(201);
      await engine.idle();
      expect((await prisma.userBadge.findMany({ where: { userId: id } })).map((b) => b.badgeCode)).toEqual(['FIRST_MEASUREMENT']);
    });

    it('a failing evaluation never fails or blocks the write, and is logged', async () => {
      const { token, id } = await signUp();
      const warn = jest.spyOn(Logger.prototype, 'warn').mockImplementation(() => undefined);
      const boom = jest.spyOn(engine, 'evaluate').mockRejectedValue(new Error('db down'));
      try {
        const today = localDate(new Date(), tz);
        await http().post('/api/v1/water-logs').set('Authorization', `Bearer ${token}`).set('Idempotency-Key', randomUUID()).send({ date: today, amountMl: 500 }).expect(201);
        await engine.idle();
        expect(boom).toHaveBeenCalledWith(id);
        expect(warn).toHaveBeenCalledWith(expect.stringContaining('evaluate failed'));
      } finally {
        boom.mockRestore();
        warn.mockRestore();
      }
    });

    it('re-dating an old entry through PATCH does not make it count; awarded badges stay', async () => {
      const { token, id } = await signUp();
      const auth = { Authorization: `Bearer ${token}` };
      const today = localDate(new Date(), tz);
      const minus = (n: number) => { let x = today; for (let i = 0; i < n; i += 1) x = new Date(Date.parse(`${x}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10); return x; };
      // Logged today for today: counts and earns FIRST_MEASUREMENT.
      const m = (await http().post('/api/v1/body-measurements').set(auth).set('Idempotency-Key', randomUUID()).send({ date: today, weightKg: 70 }).expect(201)).body;
      await engine.idle();
      expect((await prisma.userBadge.findMany({ where: { userId: id } })).map((b) => b.badgeCode)).toEqual(['FIRST_MEASUREMENT']);
      // Re-dated 5 days back: the edit time (today) is outside that date's window, so it stops counting...
      await http().patch(`/api/v1/body-measurements/${m.id}`).set(auth).send({ date: minus(5) }).expect(200);
      expect((await prisma.bodyMeasurement.findUniqueOrThrow({ where: { id: m.id } })).dateChangedAt).not.toBeNull();
      expect((await engine.facts(id, new Date())).measurementDates).toEqual([]);
      // ...a water entry created long ago and re-dated to yesterday doesn't count either...
      const w = await prisma.waterLog.create({ data: { userId: id, date: d(minus(30)), amountMl: 2500, createdAt: at(minus(30), 9) } });
      await http().patch(`/api/v1/water-logs/${w.id}`).set(auth).send({ date: minus(10) }).expect(200);
      expect((await engine.facts(id, new Date())).waterByDate.size).toBe(0);
      // ...while re-dating to yesterday today is within the window and counts (same rule as logging late).
      await http().patch(`/api/v1/water-logs/${w.id}`).set(auth).send({ date: minus(1) }).expect(200);
      expect((await engine.facts(id, new Date())).waterByDate.get(minus(1))).toBe(2500);
      // Changing only the amount keeps the date window.
      await http().patch(`/api/v1/water-logs/${w.id}`).set(auth).send({ amountMl: 3000 }).expect(200);
      expect((await engine.facts(id, new Date())).waterByDate.get(minus(1))).toBe(3000);
      // Nothing already awarded is revoked.
      await engine.evaluate(id);
      expect(await prisma.userBadge.count({ where: { userId: id } })).toBe(1);
    });

    it('a hook lost before it ran (e.g. a restart) is caught up by the next evaluation', async () => {
      const { token, id } = await signUp();
      // A workout committed but its evaluation never ran (process restarted).
      await workout(id, new Date(Date.now() - 60_000));
      expect(await prisma.userBadge.count({ where: { userId: id } })).toBe(0);
      // Any later write re-evaluates the user's whole state, so the missed badge is awarded.
      const today = localDate(new Date(), tz);
      await http().post('/api/v1/water-logs').set('Authorization', `Bearer ${token}`).set('Idempotency-Key', randomUUID()).send({ date: today, amountMl: 250 }).expect(201);
      await engine.idle();
      expect((await prisma.userBadge.findMany({ where: { userId: id } })).map((b) => b.badgeCode)).toEqual(['FIRST_WORKOUT']);
    });

    it('a rejected write schedules nothing', async () => {
      const { token } = await signUp();
      const spy = jest.spyOn(engine, 'afterWrite');
      try {
        await http().post('/api/v1/water-logs').set('Authorization', `Bearer ${token}`).set('Idempotency-Key', randomUUID()).send({ date: 'nope', amountMl: 500 }).expect(400);
        await http().delete(`/api/v1/water-logs/${randomUUID()}`).set('Authorization', `Bearer ${token}`).expect(404);
        expect(spy).not.toHaveBeenCalled();
      } finally {
        spy.mockRestore();
      }
    });
  });

  describe('water challenges use dated targets', () => {
    const water = (userId: string, date: string, amountMl: number) =>
      prisma.waterLog.create({ data: { userId, date: d(date), amountMl, createdAt: at(date, 9) } });
    const goal = (userId: string, date: string, waterMl: number | null, kcal: number | null = null) =>
      upsertRow(prisma, userId, date, { ...NO_TARGETS, waterMl, kcal });
    const waterChallenge = (userId: string, target: number) =>
      challenge(userId, { templateCode: 'WEEKLY_WATER_DAYS', target, waterGoalMl: 3000 });
    const reload = (id: string) => prisma.userChallenge.findUniqueOrThrow({ where: { id } });

    it('a goal changed mid-week: each day is judged against its own goal, not the join or current one', async () => {
      const u = await user({ dailyWaterTargetMl: 3000 });
      await goal(u, '1900-01-01', 2000);
      await goal(u, '2026-10-21', 3000); // raised on Wednesday
      await water(u, '2026-10-19', 2500); // Mon: 2500 >= 2000
      await water(u, '2026-10-20', 2000); // Tue: 2000 >= 2000
      await water(u, '2026-10-21', 2500); // Wed: 2500 < 3000
      const c = await waterChallenge(u, 3);
      await engine.evaluate(u, NOW);
      expect(await reload(c.id)).toMatchObject({ status: 'active', progress: 2, waterGoalMl: 3000 });
      await water(u, '2026-10-21', 500); // Wed reaches 3000
      await engine.evaluate(u, NOW);
      expect(await reload(c.id)).toMatchObject({ status: 'completed', progress: 3 });
    });

    it('a day before the first target, or with water unset, uses 2000 ml', async () => {
      const u = await user({ dailyWaterTargetMl: 3000 });
      await goal(u, '2026-10-20', 3000); // first target ever: Tuesday
      await goal(u, '2026-10-21', null, 1800); // Wednesday: water cleared, kcal set
      await water(u, '2026-10-19', 2000); // Mon, before the first target: 2000 >= 2000
      await water(u, '2026-10-20', 2500); // Tue: 2500 < 3000
      await water(u, '2026-10-21', 2000); // Wed, water unset: 2000 >= 2000
      const c = await waterChallenge(u, 5);
      await engine.evaluate(u, NOW);
      expect(await reload(c.id)).toMatchObject({ status: 'active', progress: 2 });
    });

    const hydrated = async (userId: string) => (await prisma.userBadge.findMany({ where: { userId, badgeCode: 'HYDRATED_7' } })).length;

    it('HYDRATED_7: a goal raised in the middle of a streak judges each day against its own goal', async () => {
      const u = await user({ dailyWaterTargetMl: 3000 });
      await goal(u, '1900-01-01', 2000);
      await goal(u, '2026-10-16', 3000); // raised on day 4 of a 7-day run (10-13..10-19)
      for (const day of days('2026-10-13', 7)) await water(u, day, 2500);
      await engine.evaluate(u, NOW);
      expect(await hydrated(u)).toBe(0); // only 10-13..10-15 reach their goal (2000); 2500 < 3000 after
      // Old days stay judged at 2000 (the current 3000 would fail them); topping up the later days earns it.
      for (const day of days('2026-10-16', 4)) await water(u, day, 500);
      await engine.evaluate(u, NOW);
      expect(await hydrated(u)).toBe(1);
    });

    it('HYDRATED_7: an already-awarded badge stays awarded when dated goals would no longer meet it', async () => {
      const u = await user();
      await goal(u, '1900-01-01', 2000);
      for (const day of days('2026-10-13', 7)) await water(u, day, 2000);
      await engine.evaluate(u, NOW);
      expect(await hydrated(u)).toBe(1);
      const xp = (await ledger(u, 'badge')).length;
      await prisma.profileTargetHistory.updateMany({ where: { userId: u }, data: { dailyWaterTargetMl: 9000 } });
      await engine.evaluate(u, NOW);
      expect(await hydrated(u)).toBe(1);
      expect((await ledger(u, 'badge')).length).toBe(xp);
    });

    it('already-completed challenges are never revoked when dated targets would no longer meet them', async () => {
      const u = await user();
      await goal(u, '1900-01-01', 9000);
      await water(u, '2026-10-19', 2500);
      const c = await waterChallenge(u, 3);
      await prisma.userChallenge.update({ where: { id: c.id }, data: { status: 'completed', progress: 3, completedAt: at('2026-10-20'), xpAwarded: 30 } });
      await engine.evaluate(u, NOW);
      expect(await reload(c.id)).toMatchObject({ status: 'completed', progress: 3, xpAwarded: 30 });
    });
  });
});
