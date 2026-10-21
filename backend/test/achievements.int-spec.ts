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

    it('uses the profile water goal, else 2000 ml', async () => {
      const withGoal = await user({ dailyWaterTargetMl: 3000 });
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
});
