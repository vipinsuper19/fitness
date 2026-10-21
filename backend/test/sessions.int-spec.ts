/**
 * T12 over HTTP against Postgres (DB_TESTS=1): sessions, set upserts,
 * pause/resume, completion with server-side XP, discard with reversal,
 * history and the read-only XP ledger.
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

describeDb('T12: workout sessions and XP (HTTP + Postgres)', () => {
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
      delete: (url: string, key: string | null = randomUUID()) => keyed(http().delete(url), key),
    };
  };
  const signUp = async () => {
    const res = await http().post('/api/v1/auth/register').send({ email: `t12-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201);
    return { token: res.body.accessToken as string, id: res.body.user.id as string };
  };
  const startSession = async (token: string, body: object = {}) => (await api(token).post('/api/v1/workout-sessions').send(body).expect(201)).body;
  const logSet = (token: string, sessionId: string, body: object = {}) =>
    api(token).post(`/api/v1/workout-sessions/${sessionId}/sets`).send({ clientSetId: randomUUID(), exerciseId: PUSH_UP, setIndex: 0, reps: 12, completed: true, ...body });

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  describe('start', () => {
    it('requires an Idempotency-Key and replays a retried start', async () => {
      const u = await signUp();
      const missing = await api(u.token).post('/api/v1/workout-sessions', null).send({}).expect(400);
      expect(missing.body.details.fields).toHaveProperty(['Idempotency-Key']);
      const key = randomUUID();
      const a = await api(u.token).post('/api/v1/workout-sessions', key).send({}).expect(201);
      const b = await api(u.token).post('/api/v1/workout-sessions', key).send({}).expect(201);
      expect(b.body.id).toBe(a.body.id);
      expect(b.headers['idempotent-replayed']).toBe('true');
      expect(await prisma.workoutSession.count({ where: { userId: u.id } })).toBe(1);
      expect(a.body).toMatchObject({ status: 'in_progress', planId: null, planVersion: null, completedAt: null, sets: [] });
    });

    it('starts from a planned workout of the user, checks planVersion, hides other users’ plans', async () => {
      const u = await signUp();
      const other = await signUp();
      const draft = (await api(u.token).post('/api/v1/workout-plans/generate', null).send({ goal: 'maintain', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 30 }).expect(200)).body;
      const plan = (await api(u.token).post('/api/v1/workout-plans').send(draft).expect(201)).body;
      const pw = plan.workouts[0].id;
      const s = await startSession(u.token, { plannedWorkoutId: pw, planVersion: 1 });
      expect(s).toMatchObject({ planId: plan.id, planVersion: 1, plannedWorkoutId: pw });
      await api(u.token).post('/api/v1/workout-sessions').send({ plannedWorkoutId: pw, planVersion: 2 }).expect(400);
      await api(u.token).post('/api/v1/workout-sessions').send({ planVersion: 1 }).expect(400);
      await api(other.token).post('/api/v1/workout-sessions').send({ plannedWorkoutId: pw }).expect(404);
    });

    it('accepts an offline start up to 24 h old, but not older or in the future', async () => {
      const u = await signUp();
      const past = new Date(Date.now() - 3600_000).toISOString();
      expect((await startSession(u.token, { startedAt: past })).startedAt).toBe(past);
      const recent = new Date(Date.now() - 23 * 3600_000).toISOString();
      expect((await startSession(u.token, { startedAt: recent })).startedAt).toBe(recent);
      const stale = await api(u.token).post('/api/v1/workout-sessions').send({ startedAt: new Date(Date.now() - 25 * 3600_000).toISOString() }).expect(400);
      expect(stale.body.code).toBe('VALIDATION_FAILED');
      expect(stale.body.details.fields).toHaveProperty('startedAt');
      const future = new Date(Date.now() + 3600_000).toISOString();
      const r = await api(u.token).post('/api/v1/workout-sessions').send({ startedAt: future }).expect(400);
      expect(r.body.details.fields).toHaveProperty('startedAt');
    });
  });

  describe('sets', () => {
    it('upserts by clientSetId: a re-send replaces the set instead of adding one', async () => {
      const u = await signUp();
      const s = await startSession(u.token);
      const clientSetId = randomUUID();
      const first = (await logSet(u.token, s.id, { clientSetId, reps: 10, weightKg: 12.5 }).expect(200)).body;
      const second = (await logSet(u.token, s.id, { clientSetId, reps: 8, weightKg: 15 }).expect(200)).body;
      expect(second.id).toBe(first.id);
      expect(second).toMatchObject({ clientSetId, reps: 8, weightKg: 15, completed: true });
      const session = (await api(u.token).get(`/api/v1/workout-sessions/${s.id}`).expect(200)).body;
      expect(session.sets).toHaveLength(1);
    });

    it('rejects a clientSetId from another session, unknown exercises and missing keys', async () => {
      const u = await signUp();
      const s1 = await startSession(u.token);
      const s2 = await startSession(u.token);
      const clientSetId = randomUUID();
      await logSet(u.token, s1.id, { clientSetId }).expect(200);
      const clash = await logSet(u.token, s2.id, { clientSetId }).expect(409);
      expect(clash.body.code).toBe('CLIENT_SET_CONFLICT');
      const unknown = await logSet(u.token, s1.id, { exerciseId: randomUUID() }).expect(400);
      expect(unknown.body.details.fields).toHaveProperty('exerciseId');
      await api(u.token).post(`/api/v1/workout-sessions/${s1.id}/sets`, null).send({ clientSetId: randomUUID(), exerciseId: PUSH_UP, setIndex: 0, completed: true }).expect(400);
      await logSet(u.token, s1.id, { reps: -1 }).expect(400);
    });

    it("returns 404 for another user's session on every verb", async () => {
      const u = await signUp();
      const other = await signUp();
      const s = await startSession(u.token);
      await api(other.token).get(`/api/v1/workout-sessions/${s.id}`).expect(404);
      await api(other.token).patch(`/api/v1/workout-sessions/${s.id}`).send({ status: 'paused' }).expect(404);
      await logSet(other.token, s.id).expect(404);
      await api(other.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({}).expect(404);
      await api(other.token).delete(`/api/v1/workout-sessions/${s.id}`).expect(404);
      await api(u.token).get(`/api/v1/workout-sessions/${s.id}`).expect(200);
      await api(u.token).get('/api/v1/workout-sessions/not-a-uuid').expect(404);
    });
  });

  describe('pause, resume and active time', () => {
    it('counts only running time', async () => {
      const u = await signUp();
      const s = await startSession(u.token);
      // Pretend the session has been running for 10 minutes.
      await prisma.workoutSession.update({ where: { id: s.id }, data: { activeSince: new Date(Date.now() - 600_000) } });
      const paused = (await api(u.token).patch(`/api/v1/workout-sessions/${s.id}`).send({ status: 'paused', notes: 'water break' }).expect(200)).body;
      expect(paused.status).toBe('paused');
      expect(paused.notes).toBe('water break');
      expect(paused.activeSeconds).toBeGreaterThanOrEqual(600);
      expect(paused.activeSeconds).toBeLessThan(610);
      // Paused for "an hour": nothing accrues.
      await prisma.workoutSession.update({ where: { id: s.id }, data: { pausedAt: new Date(Date.now() - 3600_000) } });
      const still = (await api(u.token).get(`/api/v1/workout-sessions/${s.id}`).expect(200)).body;
      expect(still.activeSeconds).toBe(paused.activeSeconds);
      const resumed = (await api(u.token).patch(`/api/v1/workout-sessions/${s.id}`).send({ status: 'in_progress' }).expect(200)).body;
      expect(resumed.status).toBe('in_progress');
      expect(resumed.activeSeconds).toBeLessThan(paused.activeSeconds + 5);
      // Pausing twice is a no-op.
      await api(u.token).patch(`/api/v1/workout-sessions/${s.id}`).send({ status: 'paused' }).expect(200);
      const again = (await api(u.token).patch(`/api/v1/workout-sessions/${s.id}`).send({ status: 'paused' }).expect(200)).body;
      expect(again.status).toBe('paused');
      await api(u.token).patch(`/api/v1/workout-sessions/${s.id}`).send({ status: 'completed' }).expect(400);
    });
  });

  describe('complete and XP', () => {
    it('refuses an empty session with 422 SESSION_EMPTY', async () => {
      const u = await signUp();
      const s = await startSession(u.token);
      await logSet(u.token, s.id, { completed: false }).expect(200);
      const r = await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({}).expect(422);
      expect(r.body.code).toBe('SESSION_EMPTY');
      await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`, null).send({}).expect(400);
    });

    it('awards XP once on the server; replays and re-completions return the original result', async () => {
      const u = await signUp();
      const s = await startSession(u.token);
      for (let i = 0; i < 3; i += 1) await logSet(u.token, s.id, { setIndex: i, exerciseId: i === 2 ? SQUAT : PUSH_UP }).expect(200);
      await prisma.workoutSession.update({ where: { id: s.id }, data: { activeSince: new Date(Date.now() - 20 * 60_000) } });

      const key = randomUUID();
      const done = await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`, key).send({ perceivedDifficulty: 3, notes: 'good one' }).expect(200);
      // 20 base + 3 sets x 2 + 4 (20 active minutes / 5) = 30
      expect(done.body.xpAwarded).toBe(30);
      expect(done.body.xpBalance).toEqual({ total: 30, level: 1, xpIntoLevel: 30, xpForNextLevel: 100 });
      expect(done.body.session).toMatchObject({ status: 'completed', perceivedDifficulty: 3, notes: 'good one' });
      expect(done.body.session.activeSeconds).toBeGreaterThanOrEqual(1200);

      const replay = await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`, key).send({ perceivedDifficulty: 3, notes: 'good one' }).expect(200);
      expect(replay.headers['idempotent-replayed']).toBe('true');
      expect(replay.body).toEqual(done.body);

      const again = await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({}).expect(200);
      expect(again.body.xpAwarded).toBe(30);
      expect(again.body.xpBalance.total).toBe(30);
      expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(1);

      // Completed sessions are read-only.
      expect((await logSet(u.token, s.id).expect(409)).body.code).toBe('SESSION_COMPLETED');
      expect((await api(u.token).patch(`/api/v1/workout-sessions/${s.id}`).send({ notes: 'edit' }).expect(409)).body.code).toBe('SESSION_COMPLETED');
    });

    it('parallel completions with different keys award exactly once', async () => {
      const u = await signUp();
      const s = await startSession(u.token);
      await logSet(u.token, s.id).expect(200);
      const results = await Promise.all(Array.from({ length: 4 }, () => api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({})));
      expect(results.map((r) => [r.status, r.body.code])).toEqual(Array(4).fill([200, undefined]));
      expect(new Set(results.map((r) => r.body.xpAwarded))).toEqual(new Set([22]));
      expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(1);
    });

    it('rejects a completion time before the start or in the future', async () => {
      const u = await signUp();
      const s = await startSession(u.token);
      await logSet(u.token, s.id).expect(200);
      await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({ completedAt: new Date(Date.now() - 86_400_000).toISOString() }).expect(400);
      await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({ completedAt: new Date(Date.now() + 86_400_000).toISOString() }).expect(400);
      await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({ perceivedDifficulty: 6 }).expect(400);
    });
  });

  describe('discard and reversal', () => {
    it('reverses the award once, keeps both entries, and never re-awards', async () => {
      const u = await signUp();
      const s = await startSession(u.token);
      await logSet(u.token, s.id).expect(200);
      const completeKey = randomUUID();
      const done = (await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`, completeKey).send({}).expect(200)).body;
      expect(done.xpAwarded).toBe(22);

      const key = randomUUID();
      await api(u.token).delete(`/api/v1/workout-sessions/${s.id}`, key).expect(204);
      const replay = await api(u.token).delete(`/api/v1/workout-sessions/${s.id}`, key).expect(204);
      expect(replay.headers['idempotent-replayed']).toBe('true');
      await api(u.token).delete(`/api/v1/workout-sessions/${s.id}`).expect(404);
      await api(u.token).get(`/api/v1/workout-sessions/${s.id}`).expect(404);

      const balance = (await api(u.token).get('/api/v1/xp/balance').expect(200)).body;
      expect(balance).toEqual({ total: 0, level: 1, xpIntoLevel: 0, xpForNextLevel: 100 });
      const ledger = (await api(u.token).get('/api/v1/xp/ledger').expect(200)).body.items;
      expect(ledger).toHaveLength(2);
      const award = ledger.find((e: { sourceType: string }) => e.sourceType === 'workout_session');
      // Since session correction (PRD 5.3) a discard is its own session_correction row.
      const reversal = ledger.find((e: { sourceType: string }) => e.sourceType === 'session_correction');
      expect(award).toMatchObject({ sourceId: s.id, amount: 22 });
      const row = await prisma.sessionCorrection.findUniqueOrThrow({ where: { id: reversal.sourceId } });
      expect(row).toMatchObject({ sessionId: s.id, kind: 'discard', xpDelta: -22 });
      expect(reversal).toMatchObject({ amount: -22 });

      // A completion retried with the old key replays the stored result; nothing is re-awarded.
      const old = await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`, completeKey).send({}).expect(200);
      expect(old.headers['idempotent-replayed']).toBe('true');
      await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({}).expect(404);
      expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(2);
      expect((await api(u.token).get('/api/v1/xp/balance').expect(200)).body.total).toBe(0);
    });

    it('discarding an unfinished session writes no XP', async () => {
      const u = await signUp();
      const s = await startSession(u.token);
      await logSet(u.token, s.id).expect(200);
      await api(u.token).delete(`/api/v1/workout-sessions/${s.id}`).expect(204);
      expect(await prisma.xPTransaction.count({ where: { userId: u.id } })).toBe(0);
      expect(await prisma.exerciseSetLog.count({ where: { sessionId: s.id } })).toBe(0);
    });
  });

  describe('history and ledger', () => {
    it('lists completed sessions newest first, by local (IST) date, with validated cursors', async () => {
      const u = await signUp();
      const finish = async (completedAt: string) => {
        // Fixed past dates are older than the 24 h start window, so back-date in the DB.
        const s = await startSession(u.token);
        const startedAt = new Date(new Date(completedAt).getTime() - 1800_000);
        await prisma.workoutSession.update({ where: { id: s.id }, data: { startedAt, activeSince: startedAt } });
        await logSet(u.token, s.id).expect(200);
        await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({ completedAt }).expect(200);
        return s.id as string;
      };
      // 2026-10-19T19:00Z is 00:30 on 20 Oct in Asia/Kolkata.
      const late = await finish('2026-10-19T19:00:00.000Z');
      const early = await finish('2026-10-19T10:00:00.000Z');
      const older = await finish('2026-10-18T10:00:00.000Z');
      await startSession(u.token); // in progress: never in history

      const all = (await api(u.token).get('/api/v1/workout-sessions/history').expect(200)).body;
      expect(all.items.map((s: { id: string }) => s.id)).toEqual([late, early, older]);
      expect(all.nextCursor).toBeNull();
      const day20 = (await api(u.token).get('/api/v1/workout-sessions/history?from=2026-10-20&to=2026-10-20').expect(200)).body;
      expect(day20.items.map((s: { id: string }) => s.id)).toEqual([late]);
      const day19 = (await api(u.token).get('/api/v1/workout-sessions/history?from=2026-10-19&to=2026-10-19').expect(200)).body;
      expect(day19.items.map((s: { id: string }) => s.id)).toEqual([early]);
      await api(u.token).get('/api/v1/workout-sessions/history?from=2026-10-20&to=2026-10-19').expect(400);

      const p1 = (await api(u.token).get('/api/v1/workout-sessions/history?limit=2').expect(200)).body;
      const p2 = (await api(u.token).get(`/api/v1/workout-sessions/history?limit=2&cursor=${p1.nextCursor}`).expect(200)).body;
      expect([...p1.items, ...p2.items].map((s: { id: string }) => s.id)).toEqual([late, early, older]);
      expect(p2.nextCursor).toBeNull();

      const forge = (v: unknown) => Buffer.from(JSON.stringify(v)).toString('base64url');
      for (const c of ['garbage', forge({ t: '2026-10-19T10:00:00.000Z', id: 'x' }), forge({ t: 'yesterday', id: randomUUID() })]) {
        const r = await api(u.token).get(`/api/v1/workout-sessions/history?cursor=${c}`).expect(400);
        expect(r.body.details.fields).toHaveProperty('cursor');
        await api(u.token).get(`/api/v1/xp/ledger?cursor=${c}`).expect(400);
      }

      // Others see none of it.
      const other = await signUp();
      expect((await api(other.token).get('/api/v1/workout-sessions/history').expect(200)).body.items).toEqual([]);
      expect((await api(other.token).get('/api/v1/xp/ledger').expect(200)).body.items).toEqual([]);
    });

    it('pages the ledger and has no client write endpoint', async () => {
      const u = await signUp();
      for (let i = 0; i < 3; i += 1) {
        const s = await startSession(u.token);
        await logSet(u.token, s.id).expect(200);
        await api(u.token).post(`/api/v1/workout-sessions/${s.id}/complete`).send({}).expect(200);
      }
      const p1 = (await api(u.token).get('/api/v1/xp/ledger?limit=2').expect(200)).body;
      const p2 = (await api(u.token).get(`/api/v1/xp/ledger?limit=2&cursor=${p1.nextCursor}`).expect(200)).body;
      expect(p1.items).toHaveLength(2);
      expect(p2.items).toHaveLength(1);
      expect(p2.nextCursor).toBeNull();
      expect((await api(u.token).get('/api/v1/xp/balance').expect(200)).body.total).toBe(66);
      await api(u.token).post('/api/v1/xp/ledger').send({ amount: 1000 }).expect(404);
    });
  });
});
