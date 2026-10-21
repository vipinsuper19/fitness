/**
 * Plan management over HTTP against Postgres (DB_TESTS=1): list, pause/resume,
 * archive (docs/specs/plan-management.md), checked against contracts/openapi.yaml.
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';
import { parse } from 'yaml';

import { seed } from '../prisma/seed';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const schemas = (parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: { schemas: Record<string, { required?: string[] }> } }).components.schemas;
const DAY = 86_400_000;

describeDb('plan management (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const as = (token: string) => {
    const auth = <T extends request.Test>(r: T, key?: string) => (key ? r.set('Authorization', `Bearer ${token}`).set('Idempotency-Key', key) : r.set('Authorization', `Bearer ${token}`));
    return {
      get: (url: string) => auth(http().get(url)),
      post: (url: string, key?: string) => auth(http().post(url), key),
      patch: (url: string) => auth(http().patch(url)),
      delete: (url: string, key?: string) => auth(http().delete(url), key),
    };
  };
  const signUp = async () =>
    (await http().post('/api/v1/auth/register').send({ email: `plans-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body.accessToken as string;
  const draft = { goal: 'maintain', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 30, weeks: 4 };
  const newPlan = async (token: string) => {
    const d = (await as(token).post('/api/v1/workout-plans/generate').send(draft).expect(200)).body;
    return (await as(token).post('/api/v1/workout-plans').send(d).expect(201)).body as { id: string; workouts: { id: string; exercises: { exerciseId: string }[] }[] };
  };
  const plan = (token: string, id: string) => as(token).get(`/api/v1/workout-plans/${id}`).expect(200).then((r) => r.body);
  const activate = (token: string, id: string, body?: object) => as(token).post(`/api/v1/workout-plans/${id}/activate`).send(body ?? {}).expect(200).then((r) => r.body);
  const pause = (token: string, id: string, key?: string) => as(token).post(`/api/v1/workout-plans/${id}/deactivate`, key).expect(204);
  const list = (token: string, qs = '') => as(token).get(`/api/v1/workout-plans${qs}`).expect(200).then((r) => r.body);

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  afterAll(async () => {
    await app.close();
  });

  it('lists summaries in the contract shape, filters by status and pages newest first', async () => {
    const t = await signUp();
    const a = await newPlan(t);
    const b = await newPlan(t);
    const c = await newPlan(t);
    await activate(t, a.id);
    const all = await list(t);
    expect(all.items.map((p: { id: string }) => p.id)).toEqual([c.id, b.id, a.id]);
    for (const s of all.items) expect(Object.keys(s)).toEqual(expect.arrayContaining(schemas.WorkoutPlanSummary!.required!));
    expect(all.items[2]).toMatchObject({ status: 'active', isActive: true, currentWeek: 1, totalWeeks: 4, version: 1, pausedAt: null, archivedAt: null, completedSessions: 0 });
    expect(all.items[0]).toMatchObject({ status: 'inactive', isActive: false, currentWeek: null });
    expect((await list(t, '?status=active')).items.map((p: { id: string }) => p.id)).toEqual([a.id]);
    expect((await list(t, '?status=inactive')).items).toHaveLength(2);
    expect((await list(t, '?status=paused')).items).toEqual([]);
    const p1 = await list(t, '?limit=2');
    expect(p1.items).toHaveLength(2);
    const p2 = await list(t, `?limit=2&cursor=${p1.nextCursor}`);
    expect(p2).toEqual({ items: [expect.objectContaining({ id: a.id })], nextCursor: null });
    await as(t).get('/api/v1/workout-plans?status=nope').expect(400);
    await as(t).get('/api/v1/workout-plans?cursor=garbage').expect(400);
    expect((await list(await signUp())).items).toEqual([]); // owner-scoped
    await http().get('/api/v1/workout-plans').expect(401);
  });

  it('pauses idempotently: no active plan afterwards, progress fields null', async () => {
    const t = await signUp();
    const a = await newPlan(t);
    const b = await newPlan(t);
    await activate(t, a.id);
    const key = randomUUID();
    await pause(t, a.id, key);
    await pause(t, a.id, key); // same-key replay
    await as(t).get('/api/v1/workout-plans/active').expect(204);
    const view = await plan(t, a.id);
    expect(view).toMatchObject({ isActive: false, currentWeek: null, planEnded: null });
    expect(view.pausedAt).not.toBeNull();
    await pause(t, a.id); // already paused: no change
    expect((await plan(t, a.id)).pausedAt).toBe(view.pausedAt);
    await pause(t, b.id); // never active: no change
    expect((await plan(t, b.id)).pausedAt).toBeNull();
    expect((await list(t, '?status=paused')).items).toEqual([expect.objectContaining({ id: a.id, status: 'paused', currentWeek: null })]);
    await as(await signUp()).post(`/api/v1/workout-plans/${a.id}/deactivate`).expect(404);
    await as(t).post('/api/v1/workout-plans/not-a-uuid/deactivate').expect(404);
  });

  it('resume continues the week; default activate restarts at week 1; resume never paused = normal', async () => {
    const t = await signUp();
    const a = await newPlan(t);
    const b = await newPlan(t);
    await activate(t, a.id);
    const now = Date.now();
    // Active for 10 days (week 2), then paused 10 days ago.
    await prisma.workoutPlan.update({ where: { id: a.id }, data: { activatedAt: new Date(now - 20 * DAY) } });
    expect((await plan(t, a.id)).currentWeek).toBe(3);
    await prisma.workoutPlan.update({ where: { id: a.id }, data: { activatedAt: new Date(now - 10 * DAY) } });
    expect((await plan(t, a.id)).currentWeek).toBe(2);
    await pause(t, a.id);
    await prisma.workoutPlan.update({ where: { id: a.id }, data: { activatedAt: new Date(now - 20 * DAY), pausedAt: new Date(now - 10 * DAY) } });

    // Another plan active in between: A still shifts by the whole pause; B becomes inactive.
    await activate(t, b.id);
    const resumed = await activate(t, a.id, { resume: true });
    expect(resumed).toMatchObject({ isActive: true, currentWeek: 2, pausedAt: null });
    const shift = new Date(resumed.activatedAt).getTime() - (now - 20 * DAY);
    expect(shift).toBeGreaterThanOrEqual(10 * DAY);
    expect(shift).toBeLessThan(10 * DAY + 60_000);
    expect((await list(t, '?status=inactive')).items).toEqual([expect.objectContaining({ id: b.id, pausedAt: null })]);

    // Default activate after a pause restarts at week 1.
    await pause(t, a.id);
    expect((await activate(t, a.id)).currentWeek).toBe(1);
    await pause(t, a.id);
    expect((await activate(t, a.id, { resume: false })).currentWeek).toBe(1);
    // resume on a never-paused plan just activates.
    expect(await activate(t, b.id, { resume: true })).toMatchObject({ isActive: true, currentWeek: 1, pausedAt: null });
    await as(t).post(`/api/v1/workout-plans/${b.id}/activate`).send({ resume: 'yes' }).expect(400);
    await as(t).post(`/api/v1/workout-plans/${b.id}/activate`).send({ extra: 1 }).expect(400);
    await as(t).post(`/api/v1/workout-plans/${b.id}/activate`).expect(200); // no body, as today's clients send
  });

  it('archiving the active plan: GET /active is 204, plan read-only, repeat is a no-op', async () => {
    const t = await signUp();
    const a = await newPlan(t);
    await activate(t, a.id);
    await as(t).delete(`/api/v1/workout-plans/${a.id}`, randomUUID()).expect(204);
    const active = await as(t).get('/api/v1/workout-plans/active').expect(204);
    expect(active.text).toBe('');
    const view = await plan(t, a.id);
    expect(view).toMatchObject({ isActive: false, currentWeek: null, planEnded: null });
    expect(view.archivedAt).not.toBeNull();
    expect(view.pausedAt).not.toBeNull(); // deactivated first
    await as(t).delete(`/api/v1/workout-plans/${a.id}`).expect(204);
    expect((await plan(t, a.id)).archivedAt).toBe(view.archivedAt);
    expect((await list(t)).items).toEqual([]);
    expect((await list(t, '?status=archived')).items).toEqual([expect.objectContaining({ id: a.id, status: 'archived', isActive: false })]);
    expect((await as(t).post(`/api/v1/workout-plans/${a.id}/activate`).send({ resume: true }).expect(409)).body.code).toBe('PLAN_ARCHIVED');
    const edit = { ...(await as(t).post('/api/v1/workout-plans/generate').send(draft).expect(200)).body, baseVersion: 1 };
    expect((await as(t).patch(`/api/v1/workout-plans/${a.id}`).send(edit).expect(409)).body.code).toBe('PLAN_ARCHIVED');
    await pause(t, a.id); // archived: 204, no change
    expect((await plan(t, a.id)).pausedAt).toBe(view.pausedAt);
    await as(await signUp()).delete(`/api/v1/workout-plans/${a.id}`).expect(404);
  });

  it('a session started from a plan that is then archived can still be completed; sessions and XP are kept', async () => {
    const t = await signUp();
    const a = await newPlan(t);
    await activate(t, a.id);
    const w = a.workouts[0]!;
    const s = (await as(t).post('/api/v1/workout-sessions', randomUUID()).send({ plannedWorkoutId: w.id }).expect(201)).body;
    await as(t).post(`/api/v1/workout-sessions/${s.id}/sets`, randomUUID()).send({ clientSetId: randomUUID(), exerciseId: w.exercises[0]!.exerciseId, setIndex: 0, reps: 10, completed: true }).expect(200);
    await as(t).delete(`/api/v1/workout-plans/${a.id}`).expect(204);
    const done = (await as(t).post(`/api/v1/workout-sessions/${s.id}/complete`, randomUUID()).send({}).expect(200)).body;
    expect(done.session).toMatchObject({ status: 'completed', plannedWorkoutId: w.id });
    expect(done.xpAwarded).toBeGreaterThan(0);
    expect((await list(t, '?status=archived')).items[0]).toMatchObject({ id: a.id, completedSessions: 1 });
    expect((await as(t).get('/api/v1/workout-sessions/history').expect(200)).body.items).toHaveLength(1);
    expect(await prisma.workoutPlanVersion.count({ where: { planId: a.id } })).toBe(1);
  });

  describe('follow-ups', () => {
    const ONLY_DATE: Parameters<typeof jest.useFakeTimers>[0] = { doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'] };
    const at = (iso: string) => jest.setSystemTime(new Date(iso));
    afterEach(() => jest.useRealTimers());

    it('resuming a plan that already ended reports currentWeek = weeks and planEnded', async () => {
      const t = await signUp();
      const a = await newPlan(t); // 4 weeks
      await activate(t, a.id);
      await pause(t, a.id);
      const now = Date.now();
      await prisma.workoutPlan.update({ where: { id: a.id }, data: { activatedAt: new Date(now - 41 * DAY), pausedAt: new Date(now - DAY) } });
      expect(await activate(t, a.id, { resume: true })).toMatchObject({ isActive: true, currentWeek: 4, planEnded: true });
      expect((await list(t, '?status=active')).items[0]).toMatchObject({ currentWeek: 4, totalWeeks: 4 });
      await pause(t, a.id);
      expect(await activate(t, a.id)).toMatchObject({ currentWeek: 1, planEnded: false }); // default starts over
    });

    it('concurrent activations of different plans leave exactly one active plan, no 500', async () => {
      const t = await signUp();
      const plans = [await newPlan(t), await newPlan(t), await newPlan(t)];
      const res = await Promise.all(plans.map((p) => as(t).post(`/api/v1/workout-plans/${p.id}/activate`).send({})));
      expect(res.map((r) => r.status)).toEqual([200, 200, 200]);
      const active = (await list(t, '?status=active')).items;
      expect(active).toHaveLength(1);
      expect((await as(t).get('/api/v1/workout-plans/active').expect(200)).body.id).toBe(active[0].id);
      expect((await list(t)).items.filter((p: { isActive: boolean }) => p.isActive)).toHaveLength(1);
    });

    it('concurrent activations of the same plan (with and without resume) succeed once, no 500', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await activate(t, a.id);
      await pause(t, a.id);
      const res = await Promise.all([{}, { resume: true }, {}, { resume: true }].map((b) => as(t).post(`/api/v1/workout-plans/${a.id}/activate`).send(b)));
      expect(res.map((r) => r.status)).toEqual([200, 200, 200, 200]);
      expect((await list(t, '?status=active')).items).toEqual([expect.objectContaining({ id: a.id, pausedAt: null })]);
    });

    it('resume across IST midnight keeps the calendar-day count (faked clock)', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      jest.useFakeTimers(ONLY_DATE);
      at('2026-09-01T03:30:00Z'); // 09:00 IST, day 1
      await activate(t, a.id);
      at('2026-09-07T18:20:00Z'); // 23:50 IST on day 7
      expect((await plan(t, a.id)).currentWeek).toBe(1);
      await pause(t, a.id);
      at('2026-09-07T18:40:00Z'); // 00:10 IST, 8 Sep (day 8)
      const r = await activate(t, a.id, { resume: true });
      expect(r.activatedAt).toBe('2026-09-01T03:50:00.000Z'); // shifted by the 20-minute pause, same start day
      expect(r).toMatchObject({ currentWeek: 2, planEnded: false });
    });

    it('resume across IST midnight when the shift moves the start day (faked clock)', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      jest.useFakeTimers(ONLY_DATE);
      at('2026-09-01T18:15:00Z'); // 23:45 IST on 1 Sep, day 1
      await activate(t, a.id);
      at('2026-09-07T18:20:00Z'); // 23:50 IST on 7 Sep, day 7
      expect((await plan(t, a.id)).currentWeek).toBe(1);
      await pause(t, a.id);
      at('2026-09-07T18:40:00Z'); // 00:10 IST on 8 Sep
      const r = await activate(t, a.id, { resume: true });
      expect(r.activatedAt).toBe('2026-09-01T18:35:00.000Z'); // 00:05 IST on 2 Sep
      expect(r).toMatchObject({ currentWeek: 1, planEnded: false }); // still day 7 of week 1
      at('2026-09-08T18:40:00Z'); // 00:10 IST on 9 Sep: day 8
      expect((await plan(t, a.id)).currentWeek).toBe(2);
    });
  });
});
