/**
 * Plan management over HTTP against Postgres (DB_TESTS=1): list, pause/resume,
 * archive, rename, unarchive (docs/specs/plan-management.md), checked against contracts/openapi.yaml.
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
      patch: (url: string, key?: string) => auth(http().patch(url), key),
      put: (url: string, key?: string) => auth(http().put(url), key),
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

  describe('rename (PUT /workout-plans/{planId}/name)', () => {
    const rename = (t: string, id: string, name: unknown, key?: string) => as(t).put(`/api/v1/workout-plans/${id}/name`, key).send({ name });
    const versions = (id: string) => prisma.workoutPlanVersion.count({ where: { planId: id } });
    const updatedAt = async (id: string) => (await prisma.workoutPlan.findUniqueOrThrow({ where: { id } })).updatedAt.getTime();
    const editOf = async (t: string, id: string, extra: object = {}) => {
      const p = await plan(t, id);
      const rest = { weeks: p.weeks, generator: 'custom', rationale: p.rationale, workouts: p.workouts.map((w: { dayOfWeek: number; name: string; exercises: object[] }) => ({ dayOfWeek: w.dayOfWeek, name: w.name, exercises: w.exercises.map((e) => { const { exerciseId, order, sets, repsMin, repsMax, durationSeconds, targetWeightKg, restSeconds } = e as Record<string, unknown>; return { exerciseId, order, sets, repsMin, repsMax, durationSeconds, targetWeightKg, restSeconds }; }) })) };
      return { ...rest, baseVersion: p.version, ...extra };
    };

    it('renames the active plan in place: trimmed, same version, visible in list and /active', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await activate(t, a.id);
      const r = (await rename(t, a.id, '  Push Pull  ').expect(200)).body;
      expect(r).toMatchObject({ id: a.id, name: 'Push Pull', version: 1, isActive: true, currentWeek: 1 });
      expect(await versions(a.id)).toBe(1);
      expect((await as(t).get('/api/v1/workout-plans/active').expect(200)).body.name).toBe('Push Pull');
      expect((await list(t)).items[0]).toMatchObject({ id: a.id, name: 'Push Pull', version: 1 });
    });

    it('rename after a PATCH changes only the latest version', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      const before = (await plan(t, a.id)).name;
      const e1 = await editOf(t, a.id);
      await as(t).patch(`/api/v1/workout-plans/${a.id}`).send(e1).expect(200);
      expect((await rename(t, a.id, 'Renamed').expect(200)).body).toMatchObject({ version: 2, name: 'Renamed' });
      expect((await as(t).get(`/api/v1/workout-plans/${a.id}?version=1`).expect(200)).body.name).toBe(before);
      expect(await versions(a.id)).toBe(2);
    });

    it('paused and inactive plans: status, pausedAt, activatedAt and currentWeek unchanged (faked clock)', async () => {
      jest.useFakeTimers({ doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'] });
      try {
        jest.setSystemTime(new Date('2026-10-28T06:00:00Z'));
        const t = await signUp();
        const a = await newPlan(t);
        const b = await newPlan(t);
        await activate(t, a.id);
        await prisma.workoutPlan.update({ where: { id: a.id }, data: { activatedAt: new Date('2026-10-20T06:00:00Z') } });
        await pause(t, a.id);
        const pa = await plan(t, a.id);
        const pb = await plan(t, b.id);
        const ra = (await rename(t, a.id, 'Paused one').expect(200)).body;
        const rb = (await rename(t, b.id, 'Inactive one').expect(200)).body;
        for (const [x, y] of [[pa, ra], [pb, rb]]) expect({ ...y, name: x.name }).toEqual(x);
        expect((await list(t, '?status=paused')).items).toEqual([expect.objectContaining({ id: a.id, name: 'Paused one' })]);
        expect((await list(t, '?status=inactive')).items).toEqual([expect.objectContaining({ id: b.id, name: 'Inactive one' })]);
        await activate(t, a.id, { resume: true });
        expect((await plan(t, a.id)).currentWeek).toBe(2);
      } finally {
        jest.useRealTimers();
      }
    });

    it('validation: empty after trim, too long, missing, wrong type and extra fields are 400', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      for (const body of [{ name: '' }, { name: '   ' }, { name: 'x'.repeat(81) }, { name: `  ${'y'.repeat(81)}  ` }, {}, { name: 3 }, { name: 'ok', extra: 1 }]) {
        const r = await as(t).put(`/api/v1/workout-plans/${a.id}/name`).send(body).expect(400);
        expect(r.body.code).toBe('VALIDATION_FAILED');
      }
      expect((await rename(t, a.id, `  ${'z'.repeat(80)}  `).expect(200)).body.name).toBe('z'.repeat(80));
    });

    it('same name (after trim) is a 200 no-op without a write', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await rename(t, a.id, 'Same').expect(200);
      const u = await updatedAt(a.id);
      expect((await rename(t, a.id, '  Same ').expect(200)).body.name).toBe('Same');
      expect(await updatedAt(a.id)).toBe(u);
    });

    it('archived plans are read-only: 409 PLAN_ARCHIVED even for the same name', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      const name = (await plan(t, a.id)).name;
      await as(t).delete(`/api/v1/workout-plans/${a.id}`).expect(204);
      const u = await updatedAt(a.id);
      expect((await rename(t, a.id, 'New').expect(409)).body.code).toBe('PLAN_ARCHIVED');
      expect((await rename(t, a.id, name).expect(409)).body.code).toBe('PLAN_ARCHIVED');
      expect(await updatedAt(a.id)).toBe(u);
      expect((await plan(t, a.id)).name).toBe(name);
    });

    it('Idempotency-Key replays; same key with a different name is a conflict', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      const key = randomUUID();
      const first = (await rename(t, a.id, 'One', key).expect(200)).body;
      await rename(t, a.id, 'Two').expect(200);
      expect((await rename(t, a.id, 'One', key).expect(200)).body).toEqual(first);
      expect((await rename(t, a.id, 'Three', key).expect(409)).body.code).toBe('IDEMPOTENCY_KEY_REUSED');
    });

    it('401 without a token; 404 for unknown, other user and malformed ids', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await http().put(`/api/v1/workout-plans/${a.id}/name`).send({ name: 'x' }).expect(401);
      await rename(t, randomUUID(), 'x').expect(404);
      await rename(await signUp(), a.id, 'x').expect(404);
      await rename(t, 'not-a-uuid', 'x').expect(404);
    });

    it('PATCH ignores name: different, missing, empty or overlong name keeps the current name', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await rename(t, a.id, 'Kept').expect(200);
      const stale = await editOf(t, a.id, { name: 'Stale pre-rename name' });
      let v = 1;
      for (const extra of [{ name: 'Different' }, {}, { name: '' }, { name: 'x'.repeat(500) }]) {
        const body = await editOf(t, a.id, extra);
        const r = (await as(t).patch(`/api/v1/workout-plans/${a.id}`).send(body).expect(200)).body;
        expect(r).toMatchObject({ version: ++v, name: 'Kept' });
      }
      // After a rename, a stale replay (old name, correct baseVersion) keeps the renamed name.
      await rename(t, a.id, 'Renamed again').expect(200);
      const r = (await as(t).patch(`/api/v1/workout-plans/${a.id}`).send({ ...stale, baseVersion: v }).expect(200)).body;
      expect(r).toMatchObject({ version: v + 1, name: 'Renamed again' });
      expect((await as(t).get(`/api/v1/workout-plans/${a.id}?version=${v}`).expect(200)).body.name).toBe('Renamed again');
    });
  });

  describe('unarchive (POST /workout-plans/{planId}/unarchive)', () => {
    const unarchive = (t: string, id: string, key?: string) => as(t).post(`/api/v1/workout-plans/${id}/unarchive`, key);
    const archive = (t: string, id: string) => as(t).delete(`/api/v1/workout-plans/${id}`).expect(204);

    it('inactive plan comes back inactive; same version; list filters move it', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      const before = await plan(t, a.id);
      await archive(t, a.id);
      const r = (await unarchive(t, a.id).expect(200)).body;
      expect(r).toEqual({ ...before });
      expect(await prisma.workoutPlanVersion.count({ where: { planId: a.id } })).toBe(1);
      expect((await list(t, '?status=archived')).items).toEqual([]);
      expect((await list(t, '?status=inactive')).items).toEqual([expect.objectContaining({ id: a.id, status: 'inactive', archivedAt: null })]);
      // Writable again.
      expect(await activate(t, a.id)).toMatchObject({ isActive: true });
    });

    it('paused plan comes back paused with the same pausedAt; resume continues the week (faked clock)', async () => {
      jest.useFakeTimers({ doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'] });
      try {
        jest.setSystemTime(new Date('2026-10-20T06:00:00Z'));
        const t = await signUp();
        const a = await newPlan(t);
        await activate(t, a.id);
        await pause(t, a.id);
        // Activated Oct 1, paused Oct 9 (week 2), archived Oct 20.
        await prisma.workoutPlan.update({ where: { id: a.id }, data: { activatedAt: new Date('2026-10-01T06:00:00Z'), pausedAt: new Date('2026-10-09T06:00:00Z') } });
        const pausedAt = (await plan(t, a.id)).pausedAt;
        await archive(t, a.id);
        expect((await unarchive(t, a.id).expect(200)).body).toMatchObject({ pausedAt, archivedAt: null, isActive: false });
        expect((await list(t, '?status=paused')).items).toEqual([expect.objectContaining({ id: a.id, status: 'paused' })]);
        expect(await activate(t, a.id, { resume: true })).toMatchObject({ isActive: true, currentWeek: 2 });
      } finally {
        jest.useRealTimers();
      }
    });

    it('archived active plan comes back paused (archive pauses first); /active stays 204; another active plan stays active', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      const b = await newPlan(t);
      await activate(t, a.id);
      const activatedAt = (await plan(t, a.id)).activatedAt;
      await archive(t, a.id);
      const archived = await plan(t, a.id);
      expect(archived.pausedAt).toBe(archived.archivedAt);
      const r = (await unarchive(t, a.id).expect(200)).body;
      expect(r).toMatchObject({ isActive: false, pausedAt: archived.archivedAt, archivedAt: null, activatedAt });
      expect((await list(t, '?status=paused')).items).toEqual([expect.objectContaining({ id: a.id })]);
      await as(t).get('/api/v1/workout-plans/active').expect(204);
      // With another plan active meanwhile, that plan stays active.
      await archive(t, a.id);
      await activate(t, b.id);
      await unarchive(t, a.id).expect(200);
      expect((await as(t).get('/api/v1/workout-plans/active').expect(200)).body.id).toBe(b.id);
    });

    it('PATCH and activate are 409 before and 200 after', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await archive(t, a.id);
      const edit = { ...(await as(t).post('/api/v1/workout-plans/generate').send(draft).expect(200)).body, baseVersion: 1 };
      expect((await as(t).patch(`/api/v1/workout-plans/${a.id}`).send(edit).expect(409)).body.code).toBe('PLAN_ARCHIVED');
      expect((await as(t).post(`/api/v1/workout-plans/${a.id}/activate`).send({}).expect(409)).body.code).toBe('PLAN_ARCHIVED');
      await unarchive(t, a.id).expect(200);
      await as(t).patch(`/api/v1/workout-plans/${a.id}`).send(edit).expect(200);
      await activate(t, a.id);
    });

    it('not archived (active, paused, inactive): 200 with the plan, no change', async () => {
      const t = await signUp();
      const act = await newPlan(t);
      const pau = await newPlan(t);
      const ina = await newPlan(t);
      await activate(t, pau.id);
      await pause(t, pau.id);
      await activate(t, act.id);
      for (const id of [act.id, pau.id, ina.id]) {
        const before = await plan(t, id);
        const u = (await prisma.workoutPlan.findUniqueOrThrow({ where: { id } })).updatedAt.getTime();
        expect((await unarchive(t, id).expect(200)).body).toEqual(before);
        expect((await prisma.workoutPlan.findUniqueOrThrow({ where: { id } })).updatedAt.getTime()).toBe(u);
      }
    });

    it('second unarchive: 200 identical body; same Idempotency-Key replays', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await archive(t, a.id);
      const key = randomUUID();
      const first = (await unarchive(t, a.id, key).expect(200)).body;
      expect((await unarchive(t, a.id).expect(200)).body).toEqual(first);
      expect((await unarchive(t, a.id, key).expect(200)).body).toEqual(first);
    });

    it('401 without a token; 404 for unknown, other user and malformed ids', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await archive(t, a.id);
      await http().post(`/api/v1/workout-plans/${a.id}/unarchive`).expect(401);
      await unarchive(t, randomUUID()).expect(404);
      await unarchive(await signUp(), a.id).expect(404);
      await unarchive(t, 'not-a-uuid').expect(404);
      expect((await plan(t, a.id)).archivedAt).not.toBeNull();
    });

    it('sessions, history and the XP ledger are unchanged', async () => {
      const t = await signUp();
      const a = await newPlan(t);
      await activate(t, a.id);
      const w = a.workouts[0]!;
      const s = (await as(t).post('/api/v1/workout-sessions', randomUUID()).send({ plannedWorkoutId: w.id }).expect(201)).body;
      await as(t).post(`/api/v1/workout-sessions/${s.id}/sets`, randomUUID()).send({ clientSetId: randomUUID(), exerciseId: w.exercises[0]!.exerciseId, setIndex: 0, reps: 10, completed: true }).expect(200);
      await as(t).post(`/api/v1/workout-sessions/${s.id}/complete`, randomUUID()).send({}).expect(200);
      await archive(t, a.id);
      const hist = (await as(t).get('/api/v1/workout-sessions/history').expect(200)).body;
      const xp = (await as(t).get('/api/v1/xp/balance').expect(200)).body;
      await unarchive(t, a.id).expect(200);
      expect((await as(t).get('/api/v1/workout-sessions/history').expect(200)).body).toEqual(hist);
      expect((await as(t).get('/api/v1/xp/balance').expect(200)).body).toEqual(xp);
      expect((await list(t)).items[0]).toMatchObject({ id: a.id, completedSessions: 1 });
    });
  });
});
