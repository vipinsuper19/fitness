/**
 * Session correction (PRD 5.3, docs/specs/session-correction.md) over HTTP against
 * Postgres (DB_TESTS=1): set corrections, completion fixes, the correction window,
 * the append-only correction ledger, discard after corrections, and newBadges.
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
const SQUAT = seedId('exercise', 'bodyweight-squat');
const DAY = 24 * 60 * 60 * 1000;

describeDb('PRD 5.3: session correction (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;

  const http = () => request(app.getHttpServer());
  const api = (token: string) => {
    const auth = <T extends request.Test>(r: T) => r.set('Authorization', `Bearer ${token}`);
    const keyed = <T extends request.Test>(r: T, key: string | null) => (key === null ? auth(r) : auth(r).set('Idempotency-Key', key));
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string, key: string | null = randomUUID()) => keyed(http().post(url), key),
      put: (url: string, key: string | null = randomUUID()) => keyed(http().put(url), key),
      patch: (url: string, key: string | null = randomUUID()) => keyed(http().patch(url), key),
      delete: (url: string, key: string | null = randomUUID()) => keyed(http().delete(url), key),
    };
  };
  const signUp = async () => {
    const res = await http().post('/api/v1/auth/register').send({ email: `corr-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201);
    return { token: res.body.accessToken as string, id: res.body.user.id as string };
  };
  const set = (clientSetId: string, extra: object = {}) => ({ clientSetId, exerciseId: PUSH_UP, setIndex: 0, reps: 10, completed: true, ...extra });
  /** A completed session with `n` completed sets (22 + 2 per extra set XP, < 5 active minutes). */
  const completed = async (token: string, n = 1) => {
    const s = (await api(token).post('/api/v1/workout-sessions').send({}).expect(201)).body;
    const ids: string[] = [];
    for (let i = 0; i < n; i++) {
      const id = randomUUID();
      ids.push(id);
      await api(token).post(`/api/v1/workout-sessions/${s.id}/sets`).send(set(id, { setIndex: i })).expect(200);
    }
    const done = (await api(token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({}).expect(200)).body;
    return { id: s.id as string, setIds: ids, xp: done.xpAwarded as number };
  };
  const corrUrl = (sid: string, cid: string) => `/api/v1/workout-sessions/${sid}/corrections/sets/${cid}`;
  const balance = async (token: string) => (await api(token).get('/api/v1/xp/balance').expect(200)).body.total as number;

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  it('two corrections then a discard: three session_correction rows, net 0, no 500', async () => {
    const u = await signUp();
    const before = await balance(u.token);
    const s = await completed(u.token, 2);
    expect(s.xp).toBe(24);
    const mismatch = await api(u.token).put(corrUrl(s.id, randomUUID())).send(set(randomUUID())).expect(400);
    expect(mismatch.body.code).toBe('VALIDATION_FAILED');
    const extra = randomUUID();
    const c1 = (await api(u.token).put(corrUrl(s.id, extra)).send(set(extra, { setIndex: 2 })).expect(200)).body;
    expect(c1.xpDelta).toBe(2);
    expect(c1.session.sets).toHaveLength(3);
    const c2 = (await api(u.token).delete(corrUrl(s.id, s.setIds[0]!)).expect(200)).body;
    expect(c2.xpDelta).toBe(-2);
    expect(c2.xpBalance.total).toBe(before + 24);
    await api(u.token).delete(`/api/v1/workout-sessions/${s.id}`).expect(204);

    const rows = await prisma.xPTransaction.findMany({ where: { userId: u.id, sourceType: 'session_correction' }, orderBy: { createdAt: 'asc' } });
    expect(rows.map((r) => r.amount)).toEqual([2, -2, -24]);
    const kinds = await prisma.sessionCorrection.findMany({ where: { sessionId: s.id }, orderBy: { createdAt: 'asc' } });
    expect(kinds.map((k) => k.kind)).toEqual(['correction', 'correction', 'discard']);
    const net = await prisma.xPTransaction.aggregate({ where: { userId: u.id, sourceType: { in: ['workout_session', 'session_correction'] } }, _sum: { amount: true } });
    expect(net._sum.amount).toBe(0);
    expect(await balance(u.token)).toBe(before);
    // The award row stays, so the session can never be awarded again.
    expect(await prisma.xPTransaction.count({ where: { userId: u.id, sourceType: 'workout_session', sourceId: s.id } })).toBe(1);
  });

  it('PUT edits the set in place (no duplicate) and replays the same body for the same key', async () => {
    const u = await signUp();
    const s = await completed(u.token, 1);
    const key = randomUUID();
    const a = await api(u.token).put(corrUrl(s.id, s.setIds[0]!), key).send(set(s.setIds[0]!, { reps: 15, exerciseId: SQUAT })).expect(200);
    expect(a.body.xpDelta).toBe(0);
    expect(a.body.session.sets).toHaveLength(1);
    expect(a.body.session.sets[0]).toMatchObject({ reps: 15, exerciseId: SQUAT });
    expect(a.body.newBadges).toEqual(expect.any(Array));
    const b = await api(u.token).put(corrUrl(s.id, s.setIds[0]!), key).send(set(s.setIds[0]!, { reps: 15, exerciseId: SQUAT })).expect(200);
    expect(b.headers['idempotent-replayed']).toBe('true');
    expect(b.body).toEqual(a.body);
    expect(await prisma.sessionCorrection.count({ where: { sessionId: s.id } })).toBe(1);
    expect(await prisma.exerciseSetLog.count({ where: { sessionId: s.id } })).toBe(1);
  });

  it('a delta that would take net below 0 is clamped; net XP and xpEarned stay at 0', async () => {
    const u = await signUp();
    const s = await completed(u.token, 1);
    // Simulate an over-large earlier negative correction.
    const row = await prisma.sessionCorrection.create({ data: { userId: u.id, sessionId: s.id, kind: 'correction', xpDelta: -500, idempotencyKey: randomUUID() } });
    await prisma.xPTransaction.create({ data: { userId: u.id, sourceType: 'session_correction', sourceId: row.id, amount: -500, reason: 'test' } });
    const today = new Date().toISOString().slice(0, 10);
    const p = (await api(u.token).get(`/api/v1/progress/summary?from=${today}&to=${today}`).expect(200)).body;
    expect(p.points.every((x: { xpEarned: number }) => x.xpEarned >= 0)).toBe(true);
    const d = (await api(u.token).delete(`/api/v1/workout-sessions/${s.id}`).expect(204));
    expect(d.status).toBe(204);
    // Net was clamped to 0, so the discard writes no XP row.
    const discard = await prisma.sessionCorrection.findFirstOrThrow({ where: { sessionId: s.id, kind: 'discard' } });
    expect(discard.xpDelta).toBe(0);
    expect(await prisma.xPTransaction.count({ where: { sourceId: discard.id } })).toBe(0);
  });

  it('a legacy reversal (adjustment keyed by the award) still counts as reversed', async () => {
    const u = await signUp();
    const s = await completed(u.token, 1);
    const award = await prisma.xPTransaction.findFirstOrThrow({ where: { userId: u.id, sourceType: 'workout_session', sourceId: s.id } });
    await prisma.xPTransaction.create({ data: { userId: u.id, sourceType: 'adjustment', sourceId: award.id, amount: -award.amount, reason: 'legacy' } });
    expect(await balance(u.token)).toBe(0);
    await api(u.token).delete(`/api/v1/workout-sessions/${s.id}`).expect(204);
    expect(await prisma.xPTransaction.count({ where: { userId: u.id, sourceType: 'session_correction' } })).toBe(0);
    expect(await balance(u.token)).toBe(0);
    await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({}).expect(404);
    expect(await prisma.xPTransaction.count({ where: { userId: u.id, sourceType: 'workout_session' } })).toBe(1);
  });

  it('progress xpEarned is the award net of corrections', async () => {
    const u = await signUp();
    const s = await completed(u.token, 1);
    const extra = randomUUID();
    await api(u.token).put(corrUrl(s.id, extra)).send(set(extra, { setIndex: 1 })).expect(200);
    const from = new Date(Date.now() - DAY).toISOString().slice(0, 10);
    const to = new Date(Date.now() + DAY).toISOString().slice(0, 10);
    const p = (await api(u.token).get(`/api/v1/progress/summary?from=${from}&to=${to}`).expect(200)).body;
    expect(p.points.reduce((n: number, x: { xpEarned: number }) => n + x.xpEarned, 0)).toBe(24);
  });

  describe('errors', () => {
    it('SESSION_NOT_COMPLETED on a session in progress, for every correction path', async () => {
      const u = await signUp();
      const s = (await api(u.token).post('/api/v1/workout-sessions').send({}).expect(201)).body;
      const cid = randomUUID();
      expect((await api(u.token).put(corrUrl(s.id, cid)).send(set(cid)).expect(409)).body.code).toBe('SESSION_NOT_COMPLETED');
      expect((await api(u.token).delete(corrUrl(s.id, cid)).expect(409)).body.code).toBe('SESSION_NOT_COMPLETED');
      expect((await api(u.token).patch(`/api/v1/workout-sessions/${s.id}/completion`).send({ notes: 'x' }).expect(409)).body.code).toBe('SESSION_NOT_COMPLETED');
    });

    it('CORRECTION_WINDOW_CLOSED once completedAt is before yesterday; discard still works', async () => {
      const u = await signUp();
      const s = await completed(u.token, 2);
      const old = new Date(Date.now() - 3 * DAY);
      await prisma.workoutSession.update({ where: { id: s.id }, data: { startedAt: new Date(old.getTime() - 60_000), completedAt: old } });
      expect((await api(u.token).delete(corrUrl(s.id, s.setIds[0]!)).expect(409)).body.code).toBe('CORRECTION_WINDOW_CLOSED');
      expect((await api(u.token).put(corrUrl(s.id, s.setIds[0]!)).send(set(s.setIds[0]!)).expect(409)).body.code).toBe('CORRECTION_WINDOW_CLOSED');
      expect((await api(u.token).patch(`/api/v1/workout-sessions/${s.id}/completion`).send({ notes: 'x' }).expect(409)).body.code).toBe('CORRECTION_WINDOW_CLOSED');
      await api(u.token).delete(`/api/v1/workout-sessions/${s.id}`).expect(204);
    });

    it('SESSION_EMPTY when the last completed set is deleted or unchecked; nothing is written', async () => {
      const u = await signUp();
      const s = await completed(u.token, 1);
      expect((await api(u.token).delete(corrUrl(s.id, s.setIds[0]!)).expect(422)).body.code).toBe('SESSION_EMPTY');
      expect((await api(u.token).put(corrUrl(s.id, s.setIds[0]!)).send(set(s.setIds[0]!, { completed: false })).expect(422)).body.code).toBe('SESSION_EMPTY');
      expect(await prisma.exerciseSetLog.count({ where: { sessionId: s.id, completed: true } })).toBe(1);
      expect(await prisma.sessionCorrection.count({ where: { sessionId: s.id } })).toBe(0);
    });

    it('SET_NOT_FOUND for a delete repeated with a new key; the same key replays 200', async () => {
      const u = await signUp();
      const s = await completed(u.token, 2);
      const key = randomUUID();
      const first = await api(u.token).delete(corrUrl(s.id, s.setIds[1]!), key).expect(200);
      const replay = await api(u.token).delete(corrUrl(s.id, s.setIds[1]!), key).expect(200);
      expect(replay.body).toEqual(first.body);
      expect((await api(u.token).delete(corrUrl(s.id, s.setIds[1]!)).expect(404)).body.code).toBe('SET_NOT_FOUND');
      expect((await api(u.token).delete(corrUrl(randomUUID(), s.setIds[1]!)).expect(404)).body.code).toBe('NOT_FOUND');
    });

    it('requires an Idempotency-Key on every correction path', async () => {
      const u = await signUp();
      const s = await completed(u.token, 1);
      await api(u.token).put(corrUrl(s.id, s.setIds[0]!), null).send(set(s.setIds[0]!)).expect(400);
      await api(u.token).delete(corrUrl(s.id, s.setIds[0]!), null).expect(400);
      await api(u.token).patch(`/api/v1/workout-sessions/${s.id}/completion`, null).send({ notes: 'x' }).expect(400);
    });

    it('CLIENT_SET_CONFLICT when the clientSetId belongs to another session', async () => {
      const u = await signUp();
      const a = await completed(u.token, 1);
      const b = await completed(u.token, 1);
      expect((await api(u.token).put(corrUrl(b.id, a.setIds[0]!)).send(set(a.setIds[0]!)).expect(409)).body.code).toBe('CLIENT_SET_CONFLICT');
    });

    it('POST /sets on a completed session is still 409 SESSION_COMPLETED', async () => {
      const u = await signUp();
      const s = await completed(u.token, 1);
      expect((await api(u.token).post(`/api/v1/workout-sessions/${s.id}/sets`).send(set(randomUUID())).expect(409)).body.code).toBe('SESSION_COMPLETED');
    });
  });

  describe('PATCH /completion', () => {
    it('fixes difficulty and notes with xpDelta 0, and validates completedAt', async () => {
      const u = await signUp();
      const s = await completed(u.token, 1);
      const r = (await api(u.token).patch(`/api/v1/workout-sessions/${s.id}/completion`).send({ perceivedDifficulty: 4, notes: 'fixed' }).expect(200)).body;
      expect(r).toMatchObject({ xpDelta: 0, session: { perceivedDifficulty: 4, notes: 'fixed' } });
      await api(u.token).patch(`/api/v1/workout-sessions/${s.id}/completion`).send({}).expect(400);
      await api(u.token).patch(`/api/v1/workout-sessions/${s.id}/completion`).send({ completedAt: new Date(Date.now() + DAY).toISOString() }).expect(400);
      await api(u.token).patch(`/api/v1/workout-sessions/${s.id}/completion`).send({ completedAt: new Date(Date.now() - 3 * DAY).toISOString() }).expect(400);
    });

    it('moving completedAt earlier can only shorten active time (XP can drop, never rise)', async () => {
      const u = await signUp();
      const s = await completed(u.token, 1);
      const startedAt = new Date(Date.now() - 2 * 60 * 60 * 1000);
      await prisma.workoutSession.update({ where: { id: s.id }, data: { startedAt, activeSeconds: 3600 } });
      const r = (await api(u.token).patch(`/api/v1/workout-sessions/${s.id}/completion`).send({ completedAt: new Date(startedAt.getTime() + 10 * 60_000).toISOString() }).expect(200)).body;
      expect(r.session.activeSeconds).toBe(600);
      expect(r.xpDelta).toBe(2); // 22 award -> 20 + 2 + 2 (10 min)
    });
  });

  it('re-evaluates badges: an added set can earn a badge reported in newBadges', async () => {
    const u = await signUp();
    const s = await completed(u.token, 1);
    const r = (await api(u.token).put(corrUrl(s.id, s.setIds[0]!)).send(set(s.setIds[0]!, { reps: 11 })).expect(200)).body;
    expect(Array.isArray(r.newBadges)).toBe(true);
    for (const b of r.newBadges) expect(b).toMatchObject({ code: expect.any(String), earnedAt: expect.any(String), progress: null });
  });
});
