/**
 * Strength progress over HTTP against Postgres (DB_TESTS=1): every planned test in
 * docs/specs/strength-progress.md. Fixtures are written straight to the DB so
 * completedAt and set values are exact.
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { parse } from 'yaml';

import { seed } from '../prisma/seed';
import { seedId } from '../prisma/seed-data/ids';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const schemas = (parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: { schemas: Record<string, { required?: string[] }> } }).components.schemas;
const req = (n: string) => schemas[n]!.required!;

const BENCH = seedId('exercise', 'dumbbell-bench-press');
const ROW = seedId('exercise', 'dumbbell-row');
const PUSH_UP = seedId('exercise', 'push-up');
const PLANK = seedId('exercise', 'plank');
const RUN = seedId('exercise', 'running');

type SetIn = { ex: string; reps?: number | null; kg?: number | null; sec?: number | null; m?: number | null; completed?: boolean };

describeDb('strength progress (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const get = (token: string, url: string) => http().get(url).set('Authorization', `Bearer ${token}`);
  const signUp = async () => {
    const r = await http().post('/api/v1/auth/register').send({ email: `str-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201);
    return { token: r.body.accessToken as string, id: r.body.user.id as string };
  };
  /** One session completed at `at` (ISO), with its sets. */
  const session = async (userId: string, at: string, sets: SetIn[], status: 'completed' | 'in_progress' = 'completed') => {
    const completedAt = new Date(at);
    const s = await prisma.workoutSession.create({
      data: { userId, status, startedAt: new Date(completedAt.getTime() - 1_800_000), completedAt: status === 'completed' ? completedAt : null, activeSeconds: 1800 },
    });
    await prisma.exerciseSetLog.createMany({
      data: sets.map((x, i) => ({
        userId, sessionId: s.id, clientSetId: randomUUID(), exerciseId: x.ex, setIndex: i, reps: x.reps ?? null, weightKg: x.kg ?? null,
        durationSeconds: x.sec ?? null, distanceM: x.m ?? null, completed: x.completed ?? true,
      })),
    });
    return s.id;
  };
  const detail = (token: string, ex: string, qs: string) => get(token, `/api/v1/progress/exercises/${ex}?${qs}`);
  const point = (body: { points: (Record<string, unknown> & { period: string })[] }, period: string) => body.points.find((p) => p.period === period)!;

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  it('returns every period in the contract shape for day, week (Monday) and month, clipped to the range', async () => {
    const u = await signUp();
    const day = (await detail(u.token, BENCH, 'from=2026-09-03&to=2026-09-09').expect(200)).body;
    expect(Object.keys(day)).toEqual(expect.arrayContaining(req('ExerciseProgress')));
    expect(day).toMatchObject({ exerciseId: BENCH, name: 'Dumbbell bench press', trackingType: 'reps_weight', bucket: 'day', timezone: 'Asia/Kolkata' });
    expect(day.points.map((p: { period: string }) => p.period)).toEqual(['2026-09-03', '2026-09-04', '2026-09-05', '2026-09-06', '2026-09-07', '2026-09-08', '2026-09-09']);
    for (const p of day.points) expect(Object.keys(p)).toEqual(expect.arrayContaining(req('ExerciseProgressPoint')));
    expect(Object.keys(day.pr)).toEqual(expect.arrayContaining(req('PersonalRecords')));
    const week = (await detail(u.token, BENCH, 'from=2026-09-03&to=2026-09-22&bucket=week').expect(200)).body;
    expect(week.points.map((p: { period: string }) => p.period)).toEqual(['2026-09-03', '2026-09-07', '2026-09-14', '2026-09-21']);
    const month = (await detail(u.token, BENCH, 'from=2026-09-03&to=2026-11-02&bucket=month').expect(200)).body;
    expect(month.points.map((p: { period: string }) => p.period)).toEqual(['2026-09-03', '2026-10-01', '2026-11-01']);
  });

  it('validates like /progress/summary: 366 days for every bucket, 400s, 401, 404 (unknown and malformed id), never performed', async () => {
    const u = await signUp();
    for (const bucket of ['day', 'week', 'month']) {
      await detail(u.token, BENCH, `from=2025-01-01&to=2026-01-02&bucket=${bucket}`).expect(400); // 367 days
      await detail(u.token, BENCH, `from=2025-01-01&to=2026-01-01&bucket=${bucket}`).expect(200); // 366
    }
    await get(u.token, '/api/v1/progress/exercises?from=2025-01-01&to=2026-01-02').expect(400);
    await detail(u.token, BENCH, 'from=2026-09-10&to=2026-09-01').expect(400);
    await detail(u.token, BENCH, 'from=2026-02-30&to=2026-03-01').expect(400);
    await detail(u.token, BENCH, 'from=2026-09-01').expect(400);
    await detail(u.token, BENCH, 'from=2026-09-01&to=2026-09-02&bucket=year').expect(400);
    await get(u.token, '/api/v1/progress/exercises?from=2026-09-01&to=2026-09-02&cursor=garbage').expect(400);
    await http().get(`/api/v1/progress/exercises/${BENCH}?from=2026-09-01&to=2026-09-02`).expect(401);
    await http().get('/api/v1/progress/exercises?from=2026-09-01&to=2026-09-02').expect(401);
    await detail(u.token, randomUUID(), 'from=2026-09-01&to=2026-09-02').expect(404);
    expect((await detail(u.token, 'not-a-uuid', 'from=2026-09-01&to=2026-09-02').expect(404)).body.code).toBe('NOT_FOUND');
    const never = (await detail(u.token, BENCH, 'from=2026-09-01&to=2026-09-02').expect(200)).body;
    expect(Object.values(never.pr).every((v) => v === null)).toBe(true);
    expect(never.points).toEqual([
      { period: '2026-09-01', sessions: 0, sets: 0, volumeKg: null, totalReps: null, topSetWeightKg: null, best1RmKg: null, totalDurationSec: null, totalDistanceM: null, maxSetDurationSec: null, maxSetDistanceM: null },
      expect.objectContaining({ period: '2026-09-02', sessions: 0, sets: 0, volumeKg: null }),
    ]);
  });

  it('computes volume, reps, top set and Epley 1RM (1 rep = weight, 13 reps no 1RM); maxReps is the best single set', async () => {
    const u = await signUp();
    await session(u.id, '2026-09-10T06:30:00Z', [
      { ex: BENCH, reps: 10, kg: 20 }, { ex: BENCH, reps: 5, kg: 25 }, { ex: BENCH, reps: 1, kg: 30 }, { ex: BENCH, reps: 13, kg: 40 },
      { ex: BENCH, reps: 8, kg: 100, completed: false }, // not completed: ignored
    ]);
    const b = (await detail(u.token, BENCH, 'from=2026-09-10&to=2026-09-10').expect(200)).body;
    expect(b.points[0]).toEqual({
      period: '2026-09-10', sessions: 1, sets: 4, volumeKg: 875, totalReps: 29, topSetWeightKg: 40, best1RmKg: 30,
      totalDurationSec: null, totalDistanceM: null, maxSetDurationSec: null, maxSetDistanceM: null,
    });
    expect(b.pr).toMatchObject({ maxWeightKg: 40, maxReps: 13, best1RmKg: 30, maxDurationSec: null, maxDistanceM: null });
    expect(b.pr.best1RmKgAchievedAt).toBe('2026-09-10T06:30:00.000Z');
    // Epley rounding: 25 × (1 + 5/30) = 29.1666… → 29.2 once the 1-rep set is gone.
    await prisma.exerciseSetLog.deleteMany({ where: { userId: u.id, reps: 1 } });
    expect((await detail(u.token, BENCH, 'from=2026-09-10&to=2026-09-10').expect(200)).body.pr.best1RmKg).toBe(29.2);
  });

  it('bodyweight: null volume and weight, reps counted; duration and distance totals and records; nulls where they do not apply', async () => {
    const u = await signUp();
    await session(u.id, '2026-09-10T06:30:00Z', [
      { ex: PUSH_UP, reps: 10 }, { ex: PUSH_UP, reps: 10 }, { ex: PUSH_UP, reps: 12 },
      { ex: PLANK, sec: 60 }, { ex: PLANK, sec: 90 },
      { ex: RUN, sec: 1800, m: 5000.5 },
    ]);
    await session(u.id, '2026-09-11T06:30:00Z', [{ ex: RUN, sec: 1200, m: 3000 }]);
    const q = 'from=2026-09-10&to=2026-09-11';
    const push = (await detail(u.token, PUSH_UP, q).expect(200)).body;
    expect(point(push, '2026-09-10')).toMatchObject({ sets: 3, volumeKg: null, totalReps: 32, topSetWeightKg: null, best1RmKg: null, totalDurationSec: null, totalDistanceM: null });
    expect(push.pr).toMatchObject({ maxReps: 12, maxWeightKg: null, best1RmKg: null, maxDurationSec: null, maxDistanceM: null }); // best single set, not 32
    const plank = (await detail(u.token, PLANK, q).expect(200)).body;
    expect(point(plank, '2026-09-10')).toMatchObject({ sets: 2, totalDurationSec: 150, maxSetDurationSec: 90, totalReps: null, volumeKg: null, totalDistanceM: null, maxSetDistanceM: null });
    expect(plank.pr).toMatchObject({ maxDurationSec: 90, maxDurationSecAchievedAt: '2026-09-10T06:30:00.000Z', maxReps: null, maxDistanceM: null });
    const runP = (await detail(u.token, RUN, `${q}&bucket=week`).expect(200)).body;
    expect(runP.points).toEqual([expect.objectContaining({ period: '2026-09-10', sessions: 2, sets: 2, totalDurationSec: 3000, totalDistanceM: 8000.5, maxSetDurationSec: 1800, maxSetDistanceM: 5000.5, totalReps: null, volumeKg: null })]);
    expect(runP.pr).toMatchObject({ maxDistanceM: 5000.5, maxDistanceMAchievedAt: '2026-09-10T06:30:00.000Z', maxDurationSec: 1800 });
  });

  it('records are all-time: a PR outside the range still shows; the earliest session wins a tie', async () => {
    const u = await signUp();
    await session(u.id, '2026-01-05T06:30:00Z', [{ ex: BENCH, reps: 5, kg: 50 }]);
    await session(u.id, '2026-02-05T06:30:00Z', [{ ex: BENCH, reps: 5, kg: 50 }]);
    await session(u.id, '2026-09-10T06:30:00Z', [{ ex: BENCH, reps: 8, kg: 40 }]);
    const b = (await detail(u.token, BENCH, 'from=2026-09-01&to=2026-09-30&bucket=month').expect(200)).body;
    expect(b.points[0]).toMatchObject({ topSetWeightKg: 40, sessions: 1 });
    expect(b.pr).toMatchObject({ maxWeightKg: 50, maxWeightKgAchievedAt: '2026-01-05T06:30:00.000Z', maxReps: 8, maxRepsAchievedAt: '2026-09-10T06:30:00.000Z' });
    const list = (await get(u.token, '/api/v1/progress/exercises?from=2026-09-01&to=2026-09-30').expect(200)).body;
    expect(list.items).toEqual([expect.objectContaining({ exerciseId: BENCH, sessions: 1, pr: expect.objectContaining({ maxWeightKg: 50 }) })]);
  });

  it('excludes in-progress and discarded sessions; corrections apply on the next read and can lower records', async () => {
    const u = await signUp();
    await session(u.id, '2026-09-10T06:30:00Z', [{ ex: BENCH, reps: 5, kg: 60 }], 'in_progress');
    const typo = await session(u.id, '2026-09-11T06:30:00Z', [{ ex: BENCH, reps: 5, kg: 200 }]);
    const ok = await session(u.id, '2026-09-12T06:30:00Z', [{ ex: BENCH, reps: 5, kg: 30 }]);
    const q = 'from=2026-09-10&to=2026-09-12';
    let b = (await detail(u.token, BENCH, q).expect(200)).body;
    expect(point(b, '2026-09-10').sets).toBe(0);
    expect(b.pr).toMatchObject({ maxWeightKg: 200, maxWeightKgAchievedAt: '2026-09-11T06:30:00.000Z' });
    // Correction: 200 → 20 kg. The record drops to the next-best set.
    await prisma.exerciseSetLog.updateMany({ where: { sessionId: typo }, data: { weightKg: 20 } });
    b = (await detail(u.token, BENCH, q).expect(200)).body;
    expect(b.pr).toMatchObject({ maxWeightKg: 30, maxWeightKgAchievedAt: '2026-09-12T06:30:00.000Z' });
    expect(point(b, '2026-09-11')).toMatchObject({ topSetWeightKg: 20, volumeKg: 100 });
    // Discard (API): the session and its sets are gone from points and records.
    await http().delete(`/api/v1/workout-sessions/${ok}`).set('Authorization', `Bearer ${u.token}`).set('Idempotency-Key', randomUUID()).expect(204);
    b = (await detail(u.token, BENCH, q).expect(200)).body;
    expect(point(b, '2026-09-12')).toMatchObject({ sessions: 0, sets: 0, topSetWeightKg: null });
    expect(b.pr).toMatchObject({ maxWeightKg: 20, maxWeightKgAchievedAt: '2026-09-11T06:30:00.000Z' });
  });

  it('buckets 23:50 vs 00:10 IST on the right local day and Monday week, in the current profile timezone', async () => {
    const u = await signUp();
    await session(u.id, '2026-09-13T18:20:00Z', [{ ex: BENCH, reps: 5, kg: 20 }]); // 23:50 IST Sun 13 Sep
    await session(u.id, '2026-09-13T18:40:00Z', [{ ex: BENCH, reps: 5, kg: 22 }]); // 00:10 IST Mon 14 Sep
    const q = 'from=2026-09-13&to=2026-09-14';
    const day = (await detail(u.token, BENCH, q).expect(200)).body;
    expect(day.points).toEqual([expect.objectContaining({ period: '2026-09-13', sessions: 1, topSetWeightKg: 20 }), expect.objectContaining({ period: '2026-09-14', sessions: 1, topSetWeightKg: 22 })]);
    const week = (await detail(u.token, BENCH, `${q}&bucket=week`).expect(200)).body;
    expect(week.points.map((p: { period: string; sessions: number }) => [p.period, p.sessions])).toEqual([['2026-09-13', 1], ['2026-09-14', 1]]);
    // Changing the timezone re-buckets history: in UTC both are on Sunday 13 Sep.
    await prisma.userProfile.update({ where: { userId: u.id }, data: { timezone: 'UTC' } });
    const utc = (await detail(u.token, BENCH, q).expect(200)).body;
    expect(utc.timezone).toBe('UTC');
    expect(utc.points.map((p: { period: string; sessions: number }) => [p.period, p.sessions])).toEqual([['2026-09-13', 2], ['2026-09-14', 0]]);
  });

  it('lists in-range exercises by lastPerformedAt DESC then exerciseId DESC, pages with a cursor, owner-scoped, read-only', async () => {
    const u = await signUp();
    await session(u.id, '2026-08-01T06:30:00Z', [{ ex: PLANK, sec: 60 }]); // outside the range
    await session(u.id, '2026-09-10T06:30:00Z', [{ ex: PUSH_UP, reps: 10 }]);
    await session(u.id, '2026-09-12T06:30:00Z', [{ ex: BENCH, reps: 5, kg: 20 }, { ex: ROW, reps: 5, kg: 20 }]);
    await session(u.id, '2026-09-11T06:30:00Z', [{ ex: BENCH, reps: 5, kg: 25 }]);
    const xp = await prisma.xPTransaction.count({ where: { userId: u.id } });
    const url = '/api/v1/progress/exercises?from=2026-09-01&to=2026-09-30';
    const all = (await get(u.token, url).expect(200)).body;
    expect(all).toMatchObject({ from: '2026-09-01', to: '2026-09-30', timezone: 'Asia/Kolkata', nextCursor: null });
    for (const i of all.items) expect(Object.keys(i)).toEqual(expect.arrayContaining(req('ExerciseProgressSummary')));
    const tie = [BENCH, ROW].sort().reverse(); // same lastPerformedAt: exerciseId DESC
    expect(all.items.map((i: { exerciseId: string }) => i.exerciseId)).toEqual([...tie, PUSH_UP]);
    expect(all.items.find((i: { exerciseId: string }) => i.exerciseId === BENCH)).toMatchObject({ sessions: 2, lastPerformedAt: '2026-09-12T06:30:00.000Z', trackingType: 'reps_weight' });
    const seen: string[] = [];
    let cursor: string | null = null;
    do {
      const page: { items: { exerciseId: string }[]; nextCursor: string | null } = (await get(u.token, `${url}&limit=1${cursor ? `&cursor=${cursor}` : ''}`).expect(200)).body;
      expect(page.items).toHaveLength(1);
      seen.push(page.items[0]!.exerciseId);
      cursor = page.nextCursor;
    } while (cursor);
    expect(seen).toEqual([...tie, PUSH_UP]);
    expect((await get((await signUp()).token, url).expect(200)).body.items).toEqual([]);
    expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(xp);
  });
});
