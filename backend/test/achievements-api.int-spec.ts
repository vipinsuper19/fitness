/**
 * Badges & challenges HTTP API (DB_TESTS=1), checked against contracts/openapi.yaml.
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { parse } from 'yaml';

import { weekStart } from '../src/achievements/achievements.rules';
import { AchievementsService } from '../src/achievements/achievements.service';
import { localDate, nextDate } from '../src/common/time';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const schemas = (parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: { schemas: Record<string, { required?: string[] }> } }).components.schemas;
const required = (name: string) => schemas[name]!.required!;
const tz = 'Asia/Kolkata';
const addDays = (d: string, n: number) => { let x = d; for (let i = 0; i < n; i += 1) x = nextDate(x); return x; };

describeDb('badges & challenges API (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  let engine: AchievementsService;
  const http = () => request(app.getHttpServer());
  const as = (token: string) => {
    const auth = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string, key: string | null = randomUUID()) => (key === null ? auth(http().post(url)) : auth(http().post(url)).set('Idempotency-Key', key)),
    };
  };
  const signUp = async () => {
    const res = await http().post('/api/v1/auth/register').send({ email: `p1api-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201);
    return { token: res.body.accessToken as string, id: res.body.user.id as string };
  };
  const workout = (userId: string, completedAt = new Date(Date.now() - 60_000)) =>
    prisma.workoutSession.create({ data: { userId, status: 'completed', startedAt: new Date(completedAt.getTime() - 1_800_000), completedAt, activeSeconds: 1800 } });
  const today = () => localDate(new Date(), tz);

  beforeAll(async () => {
    app = await createTestApp({ env: { ACHIEVEMENTS_HOOKS: 'on' } });
    prisma = app.get(PrismaService);
    engine = app.get(AchievementsService);
  });
  afterAll(async () => {
    await engine.idle();
    await app.close();
  });

  describe('GET /badges', () => {
    it('lists the whole catalog with progress, in the contract shape', async () => {
      const u = await signUp();
      const body = (await as(u.token).get('/api/v1/badges').expect(200)).body;
      expect(body.earnedCount).toBe(0);
      expect(body.items).toHaveLength(16);
      for (const b of body.items) expect(Object.keys(b)).toEqual(expect.arrayContaining(required('Badge')));
      expect(body.items.find((b: { code: string }) => b.code === 'WORKOUTS_10')).toMatchObject({ earnedAt: null, progress: { current: 0, target: 10 }, bonusXp: 25, family: 'workout' });
    });

    it('re-evaluates on read, so a missed background evaluation heals', async () => {
      const u = await signUp();
      await workout(u.id); // written without any hook
      const body = (await as(u.token).get('/api/v1/badges').expect(200)).body;
      const first = body.items.find((b: { code: string }) => b.code === 'FIRST_WORKOUT');
      expect(first.earnedAt).not.toBeNull();
      expect(first.progress).toBeNull();
      expect(body.earnedCount).toBe(1);
      expect(body.items.find((b: { code: string }) => b.code === 'WORKOUTS_10').progress).toEqual({ current: 1, target: 10 });
      const ledger = (await as(u.token).get('/api/v1/xp/ledger').expect(200)).body.items;
      expect(ledger).toEqual([expect.objectContaining({ sourceType: 'badge', amount: 10 })]);
      await as(u.token).get('/api/v1/badges').expect(200);
      expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(1);
    });

    it('requires auth and has no write route', async () => {
      await http().get('/api/v1/badges').expect(401);
      const u = await signUp();
      await as(u.token).post('/api/v1/badges').send({ code: 'STREAK_30' }).expect(404);
    });
  });

  describe('challenges', () => {
    it('lists templates with per-target deadlines', async () => {
      const u = await signUp();
      const { items } = (await as(u.token).get('/api/v1/challenges/templates').expect(200)).body;
      expect(items).toHaveLength(6);
      for (const t of items) expect(Object.keys(t)).toEqual(expect.arrayContaining(required('ChallengeTemplate')));
      expect(items.find((t: { code: string }) => t.code === 'PERSONAL_WORKOUTS_N')).toMatchObject({ targets: [10, 20, 30], deadlineDays: [30, 60, 90] });
      expect(items.find((t: { code: string }) => t.code === 'PERSONAL_STREAK_N').deadlineDays).toBeNull();
    });

    it('joins idempotently with the window, join date and snapshots in the contract shape', async () => {
      const u = await signUp();
      const missing = await as(u.token).post('/api/v1/challenges', null).send({ templateCode: 'WEEKLY_WORKOUTS', target: 3 }).expect(400);
      expect(missing.body.details.fields).toHaveProperty(['Idempotency-Key']);
      expect((await as(u.token).post('/api/v1/challenges').send({ templateCode: 'NOPE', target: 3 }).expect(400)).body.details.fields).toHaveProperty('templateCode');
      expect((await as(u.token).post('/api/v1/challenges').send({ templateCode: 'WEEKLY_WORKOUTS', target: 7 }).expect(400)).body.details.fields).toHaveProperty('target');
      await as(u.token).post('/api/v1/challenges').send({ templateCode: 'WEEKLY_WORKOUTS', target: 3, extra: 1 }).expect(400);

      const key = randomUUID();
      const c = (await as(u.token).post('/api/v1/challenges', key).send({ templateCode: 'WEEKLY_WORKOUTS', target: 3 }).expect(201)).body;
      expect(Object.keys(c)).toEqual(expect.arrayContaining(required('Challenge')));
      const t = today();
      expect(c).toMatchObject({
        templateCode: 'WEEKLY_WORKOUTS', type: 'weekly', target: 3, progress: 0, unit: 'workouts', status: 'active', xpAwarded: 0,
        windowStart: weekStart(t), windowEnd: addDays(weekStart(t), 6), joinDate: t, timezone: tz, waterGoalMl: null, completedAt: null, leftAt: null,
      });
      const replay = await as(u.token).post('/api/v1/challenges', key).send({ templateCode: 'WEEKLY_WORKOUTS', target: 3 }).expect(201);
      expect(replay.headers['idempotent-replayed']).toBe('true');
      expect(replay.body.id).toBe(c.id);
      expect((await as(u.token).post('/api/v1/challenges', key).send({ templateCode: 'WEEKLY_WORKOUTS', target: 4 }).expect(409)).body.code).toBe('IDEMPOTENCY_KEY_REUSED');
      expect((await as(u.token).post('/api/v1/challenges').send({ templateCode: 'WEEKLY_WORKOUTS', target: 4 }).expect(409)).body.code).toBe('CHALLENGE_ALREADY_JOINED');
      expect(await prisma.userChallenge.count({ where: { userId: u.id } })).toBe(1);

      // Leave (no-op the second time); the same week can't be re-joined.
      const left = (await as(u.token).post(`/api/v1/challenges/${c.id}/leave`, null).expect(200)).body;
      expect(left).toMatchObject({ status: 'left', progress: 0 });
      expect(left.leftAt).not.toBeNull();
      expect((await as(u.token).post(`/api/v1/challenges/${c.id}/leave`, null).expect(200)).body.leftAt).toBe(left.leftAt);
      expect((await as(u.token).post('/api/v1/challenges').send({ templateCode: 'WEEKLY_WORKOUTS', target: 3 }).expect(409)).body.code).toBe('CHALLENGE_ALREADY_JOINED');
    });

    it('captures the water goal and deadlines; a personal challenge can be re-joined after leaving', async () => {
      const u = await signUp();
      await prisma.userProfile.upsert({ where: { userId: u.id }, create: { userId: u.id, dailyWaterTargetMl: 3000 }, update: { dailyWaterTargetMl: 3000 } });
      const t = today();
      const water = (await as(u.token).post('/api/v1/challenges').send({ templateCode: 'WEEKLY_WATER_DAYS', target: 3 }).expect(201)).body;
      expect(water.waterGoalMl).toBe(3000);
      const p = (await as(u.token).post('/api/v1/challenges').send({ templateCode: 'PERSONAL_WORKOUTS_N', target: 20 }).expect(201)).body;
      expect(p).toMatchObject({ type: 'personal', windowStart: t, windowEnd: addDays(t, 59), joinDate: t });
      const s = (await as(u.token).post('/api/v1/challenges').send({ templateCode: 'PERSONAL_STREAK_N', target: 7 }).expect(201)).body;
      expect(s.windowEnd).toBeNull();
      await as(u.token).post(`/api/v1/challenges/${s.id}/leave`, null).expect(200);
      const again = (await as(u.token).post('/api/v1/challenges').send({ templateCode: 'PERSONAL_STREAK_N', target: 7 }).expect(201)).body;
      expect(again.id).not.toBe(s.id);
    });

    it('limits active challenges to 5', async () => {
      const u = await signUp();
      for (const [templateCode, target] of [['WEEKLY_WORKOUTS', 5], ['WEEKLY_ACTIVE_MINUTES', 150], ['WEEKLY_WATER_DAYS', 7], ['WEEKLY_FOOD_LOG_DAYS', 7], ['PERSONAL_WORKOUTS_N', 30]] as const) {
        await as(u.token).post('/api/v1/challenges').send({ templateCode, target }).expect(201);
      }
      expect((await as(u.token).post('/api/v1/challenges').send({ templateCode: 'PERSONAL_STREAK_N', target: 30 }).expect(409)).body.code).toBe('CHALLENGE_LIMIT_REACHED');
    });

    it('a weekly challenge already met completes on join, with bonus XP; it can no longer be left', async () => {
      const u = await signUp();
      await workout(u.id);
      await workout(u.id, new Date(Date.now() - 120_000));
      const c = (await as(u.token).post('/api/v1/challenges').send({ templateCode: 'WEEKLY_WORKOUTS', target: 2 }).expect(201)).body;
      expect(c).toMatchObject({ status: 'completed', progress: 2, xpAwarded: 30 });
      const ledger = (await as(u.token).get('/api/v1/xp/ledger').expect(200)).body.items;
      expect(ledger).toEqual(expect.arrayContaining([expect.objectContaining({ sourceType: 'challenge', sourceId: c.id, amount: 30 })]));
      expect((await as(u.token).post(`/api/v1/challenges/${c.id}/leave`, null).expect(409)).body.code).toBe('CHALLENGE_NOT_ACTIVE');
    });

    it('progresses through the write hook and shows it on GET', async () => {
      const u = await signUp();
      const c = (await as(u.token).post('/api/v1/challenges').send({ templateCode: 'WEEKLY_WATER_DAYS', target: 3 }).expect(201)).body;
      await as(u.token).post('/api/v1/water-logs').send({ date: today(), amountMl: 2000 }).expect(201);
      await engine.idle();
      expect((await as(u.token).get(`/api/v1/challenges/${c.id}`).expect(200)).body.progress).toBe(1);
    });

    it('pages newest first, filters by status, and hides other users’ challenges', async () => {
      const u = await signUp();
      const ids: string[] = [];
      for (const [templateCode, target] of [['WEEKLY_WORKOUTS', 5], ['WEEKLY_ACTIVE_MINUTES', 150], ['PERSONAL_STREAK_N', 30]] as const) {
        ids.push((await as(u.token).post('/api/v1/challenges').send({ templateCode, target }).expect(201)).body.id);
      }
      await as(u.token).post(`/api/v1/challenges/${ids[0]}/leave`, null).expect(200);
      const p1 = (await as(u.token).get('/api/v1/challenges?limit=2').expect(200)).body;
      expect(p1.nextCursor).not.toBeNull();
      const p2 = (await as(u.token).get(`/api/v1/challenges?limit=2&cursor=${p1.nextCursor}`).expect(200)).body;
      expect(p2.nextCursor).toBeNull();
      expect([...p1.items, ...p2.items].map((c: { id: string }) => c.id)).toEqual([...ids].reverse());
      expect((await as(u.token).get('/api/v1/challenges?status=left').expect(200)).body.items.map((c: { id: string }) => c.id)).toEqual([ids[0]]);
      await as(u.token).get('/api/v1/challenges?status=bogus').expect(400);
      await as(u.token).get('/api/v1/challenges?cursor=garbage').expect(400);
      await as(u.token).get('/api/v1/challenges/not-a-uuid').expect(400);

      const other = await signUp();
      expect((await as(other.token).get('/api/v1/challenges').expect(200)).body.items).toEqual([]);
      await as(other.token).get(`/api/v1/challenges/${ids[1]}`).expect(404);
      await as(other.token).post(`/api/v1/challenges/${ids[1]}/leave`, null).expect(404);
      expect((await as(u.token).get(`/api/v1/challenges/${ids[1]}`).expect(200)).body.status).toBe('active');
    });
  });

  it('includes badges and challenges in the account export', async () => {
    const u = await signUp();
    await workout(u.id);
    await as(u.token).get('/api/v1/badges').expect(200);
    const c = (await as(u.token).post('/api/v1/challenges').send({ templateCode: 'PERSONAL_STREAK_N', target: 7 }).expect(201)).body;
    const body = (await as(u.token).get('/api/v1/me/export').expect(200)).body;
    expect(body.badges).toEqual([{ code: 'FIRST_WORKOUT', earnedAt: expect.any(String) }]);
    expect(body.challenges).toEqual([expect.objectContaining({ id: c.id, templateCode: 'PERSONAL_STREAK_N', joinDate: c.joinDate })]);
    expect(body.truncated).toEqual([]);
  });
});
