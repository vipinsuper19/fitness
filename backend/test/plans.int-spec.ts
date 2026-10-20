/**
 * T10 over HTTP against a migrated, seeded Postgres (DB_TESTS=1): profile,
 * goals, exercise catalog and workout plans (generate, save, version,
 * activate), including ownership, validation and idempotent retries.
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

describeDb('T10: profile, goals, exercises, workout plans (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  let alice: string;
  let bob: string;

  const http = () => request(app.getHttpServer());
  const as = (token: string) => ({
    get: (url: string) => http().get(url).set('Authorization', `Bearer ${token}`),
    post: (url: string, key?: string) => {
      const r = http().post(url).set('Authorization', `Bearer ${token}`);
      return key ? r.set('Idempotency-Key', key) : r;
    },
    put: (url: string, key?: string) => {
      const r = http().put(url).set('Authorization', `Bearer ${token}`);
      return key ? r.set('Idempotency-Key', key) : r;
    },
    patch: (url: string, key?: string) => {
      const r = http().patch(url).set('Authorization', `Bearer ${token}`);
      return key ? r.set('Idempotency-Key', key) : r;
    },
    delete: (url: string, key?: string) => {
      const r = http().delete(url).set('Authorization', `Bearer ${token}`);
      return key ? r.set('Idempotency-Key', key) : r;
    },
  });
  const signUp = async () =>
    (await http().post('/api/v1/auth/register').send({ email: `t10-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body
      .accessToken as string;

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma); // idempotent upsert of the catalog; CI only migrates
    [alice, bob] = await Promise.all([signUp(), signUp()]);
  });
  afterAll(async () => {
    await app.close();
  });

  describe('profile', () => {
    it('starts with India-first defaults and null metrics', async () => {
      const res = await as(alice).get('/api/v1/me/profile').expect(200);
      expect(res.body).toMatchObject({
        unitSystem: 'metric', timezone: 'Asia/Kolkata', locale: 'en-IN', currency: 'INR',
        heightCm: null, weightKg: null, onboardingCompleted: false, displayName: null,
      });
    });

    it('updates only the fields sent, clears with null, and rounds metric values', async () => {
      await as(alice).put('/api/v1/me/profile').send({ displayName: 'Asha', heightCm: 162.46, weightKg: 58.456, experienceLevel: 'beginner' }).expect(200);
      const res = await as(alice).put('/api/v1/me/profile').send({ weightKg: null, unitSystem: 'imperial', onboardingCompleted: true }).expect(200);
      expect(res.body).toMatchObject({ displayName: 'Asha', heightCm: 162.5, weightKg: null, experienceLevel: 'beginner', unitSystem: 'imperial', onboardingCompleted: true });
    });

    it('rejects out-of-range values, unknown fields, nulls for required fields and bad timezones', async () => {
      const res = await as(alice).put('/api/v1/me/profile').send({ heightCm: 300, foo: 1 }).expect(400);
      expect(res.body.code).toBe('VALIDATION_FAILED');
      await as(alice).put('/api/v1/me/profile').send({ unitSystem: null }).expect(400);
      const tz = await as(alice).put('/api/v1/me/profile').send({ timezone: 'Mars/Olympus' }).expect(400);
      expect(Object.keys(tz.body.details.fields)).toEqual(['timezone']);
      await as(alice).put('/api/v1/me/profile').send({ timezone: 'Asia/Kolkata' }).expect(200);
    });

    it('replays a retried PUT with the same key and rejects the key with another body', async () => {
      const key = randomUUID();
      const first = await as(bob).put('/api/v1/me/profile', key).send({ birthYear: 1994 }).expect(200);
      const again = await as(bob).put('/api/v1/me/profile', key).send({ birthYear: 1994 }).expect(200);
      expect(again.headers['idempotent-replayed']).toBe('true');
      expect(again.body).toEqual(first.body);
      const reused = await as(bob).put('/api/v1/me/profile', key).send({ birthYear: 1995 }).expect(409);
      expect(reused.body.code).toBe('IDEMPOTENCY_KEY_REUSED');
    });
  });

  describe('goals', () => {
    it('creates, lists newest first, filters, updates and deletes', async () => {
      const a = (await as(alice).post('/api/v1/goals').send({ type: 'lose_weight', startValue: 72, targetValue: 65, targetDate: '2027-03-31' }).expect(201)).body;
      expect(a).toMatchObject({ type: 'lose_weight', status: 'active', startValue: 72, targetValue: 65, targetDate: '2027-03-31' });
      const b = (await as(alice).post('/api/v1/goals').send({ type: 'workouts_per_week', targetValue: 4 }).expect(201)).body;
      const list = (await as(alice).get('/api/v1/goals').expect(200)).body.items.map((g: { id: string }) => g.id);
      expect(list.slice(0, 2)).toEqual([b.id, a.id]);

      const done = (await as(alice).patch(`/api/v1/goals/${b.id}`).send({ status: 'achieved' }).expect(200)).body;
      expect(done.status).toBe('achieved');
      const active = (await as(alice).get('/api/v1/goals?status=active').expect(200)).body.items.map((g: { id: string }) => g.id);
      expect(active).toContain(a.id);
      expect(active).not.toContain(b.id);

      await as(alice).delete(`/api/v1/goals/${a.id}`).expect(204);
      await as(alice).get(`/api/v1/goals/${a.id}`).expect(404);
    });

    it('validates values by goal type and real dates', async () => {
      const r1 = await as(alice).post('/api/v1/goals').send({ type: 'workouts_per_week', targetValue: 9 }).expect(400);
      expect(r1.body.details.fields).toHaveProperty('targetValue');
      await as(alice).post('/api/v1/goals').send({ type: 'lose_weight', targetValue: 5 }).expect(400);
      await as(alice).post('/api/v1/goals').send({ type: 'maintain', targetDate: '2027-02-30' }).expect(400);
      await as(alice).post('/api/v1/goals').send({ type: 'nap_more' }).expect(400);
    });

    it("returns 404 for another user's goal on every verb, and for non-UUID ids", async () => {
      const g = (await as(alice).post('/api/v1/goals').send({ type: 'maintain' }).expect(201)).body;
      await as(bob).get(`/api/v1/goals/${g.id}`).expect(404);
      await as(bob).patch(`/api/v1/goals/${g.id}`).send({ status: 'abandoned' }).expect(404);
      await as(bob).delete(`/api/v1/goals/${g.id}`).expect(404);
      expect((await as(bob).get('/api/v1/goals').expect(200)).body.items.map((x: { id: string }) => x.id)).not.toContain(g.id);
      const bad = await as(alice).get('/api/v1/goals/not-a-uuid').expect(404);
      expect(bad.body.code).toBe('NOT_FOUND');
      await as(alice).get(`/api/v1/goals/${g.id}`).expect(200);
    });

    it('a retried create with the same key makes one goal; a retried delete replays 204', async () => {
      const key = randomUUID();
      const [r1, r2] = [
        await as(bob).post('/api/v1/goals', key).send({ type: 'gain_muscle', targetValue: 70 }).expect(201),
        await as(bob).post('/api/v1/goals', key).send({ type: 'gain_muscle', targetValue: 70 }).expect(201),
      ];
      expect(r2.body.id).toBe(r1.body.id);
      expect(r2.headers['idempotent-replayed']).toBe('true');
      expect(await prisma.fitnessGoal.count({ where: { id: r1.body.id } })).toBe(1);

      const del = randomUUID();
      await as(bob).delete(`/api/v1/goals/${r1.body.id}`, del).expect(204);
      const replay = await as(bob).delete(`/api/v1/goals/${r1.body.id}`, del).expect(204);
      expect(replay.headers['idempotent-replayed']).toBe('true');
    });

    it('parallel creates with the same key run once', async () => {
      const key = randomUUID();
      const results = await Promise.all(
        Array.from({ length: 5 }, () => as(bob).post('/api/v1/goals', key).send({ type: 'improve_endurance' })),
      );
      const created = new Set(results.filter((r) => r.status === 201).map((r) => r.body.id));
      expect(created.size).toBe(1);
      for (const r of results) expect([201, 409]).toContain(r.status);
    });
  });

  describe('exercises', () => {
    it('searches by text (English or Hindi), muscle and equipment', async () => {
      const q = (await as(alice).get('/api/v1/exercises?query=squat&limit=100').expect(200)).body;
      expect(q.items.map((e: { slug: string }) => e.slug)).toEqual(expect.arrayContaining(['bodyweight-squat', 'barbell-back-squat']));
      const hi = (await as(alice).get(`/api/v1/exercises?query=${encodeURIComponent('सूर्य')}`).expect(200)).body;
      expect(hi.items.map((e: { slug: string }) => e.slug)).toEqual(['surya-namaskar']);
      const chest = (await as(alice).get('/api/v1/exercises?muscle=triceps&limit=100').expect(200)).body.items;
      for (const e of chest) expect([e.primaryMuscle, ...e.secondaryMuscles]).toContain('triceps');
      const db = (await as(alice).get('/api/v1/exercises?equipment=dumbbell&limit=100').expect(200)).body.items;
      expect(db.length).toBeGreaterThan(0);
      for (const e of db) expect(e.equipment).toContain('dumbbell');
    });

    it('pages with an opaque cursor without gaps or repeats', async () => {
      const all = (await as(alice).get('/api/v1/exercises?limit=100').expect(200)).body;
      expect(all.nextCursor).toBeNull();
      const seen: string[] = [];
      let cursor: string | null = null;
      do {
        const url: string = `/api/v1/exercises?limit=7${cursor ? `&cursor=${cursor}` : ''}`;
        const page: { items: { id: string }[]; nextCursor: string | null } = (await as(alice).get(url).expect(200)).body;
        seen.push(...page.items.map((e) => e.id));
        cursor = page.nextCursor;
      } while (cursor);
      expect(seen).toEqual(all.items.map((e: { id: string }) => e.id));
      const bad = await as(alice).get('/api/v1/exercises?cursor=garbage').expect(400);
      expect(bad.body.details.fields).toHaveProperty('cursor');
      await as(alice).get('/api/v1/exercises?limit=101').expect(400);
      await as(alice).get('/api/v1/exercises?muscle=neck').expect(400);
    });

    it('gets one exercise; unknown ids are 404; the catalog needs sign-in', async () => {
      const id = seedId('exercise', 'push-up');
      const one = (await as(alice).get(`/api/v1/exercises/${id}`).expect(200)).body;
      expect(one).toMatchObject({ slug: 'push-up', nameHi: 'पुश-अप', trackingType: 'reps', videoUrl: null });
      await as(alice).get(`/api/v1/exercises/${randomUUID()}`).expect(404);
      await http().get('/api/v1/exercises').expect(401);
    });
  });

  describe('workout plans', () => {
    const genReq = { goal: 'gain_muscle', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 45, equipment: ['dumbbell', 'bench'] };

    it('generates the same draft for the same input and persists nothing', async () => {
      const before = await prisma.workoutPlan.count();
      const a = (await as(alice).post('/api/v1/workout-plans/generate').send(genReq).expect(200)).body;
      const b = (await as(alice).post('/api/v1/workout-plans/generate').send(genReq).expect(200)).body;
      expect(b).toEqual(a);
      expect(a.generator).toBe('rule_based');
      expect(await prisma.workoutPlan.count()).toBe(before);
      await as(alice).post('/api/v1/workout-plans/generate').send({ ...genReq, daysPerWeek: 8 }).expect(400);
    });

    it('saves a draft as version 1, activates it, edits it into version 2 and keeps version 1 readable', async () => {
      const draft = (await as(alice).post('/api/v1/workout-plans/generate').send(genReq).expect(200)).body;
      expect((await as(alice).get('/api/v1/workout-plans/active')).status).toBe(204);

      const plan = (await as(alice).post('/api/v1/workout-plans').send(draft).expect(201)).body;
      expect(plan).toMatchObject({ version: 1, name: draft.name, weeks: 4, source: 'rule_based', isActive: false });
      expect(plan.workouts.map((w: { dayOfWeek: number }) => w.dayOfWeek)).toEqual([1, 3, 5]);
      expect(plan.workouts[0].exercises.map((e: { exerciseId: string }) => e.exerciseId)).toEqual(draft.workouts[0].exercises.map((e: { exerciseId: string }) => e.exerciseId));

      const active = (await as(alice).post(`/api/v1/workout-plans/${plan.id}/activate`).expect(200)).body;
      expect(active.isActive).toBe(true);
      expect((await as(alice).get('/api/v1/workout-plans/active').expect(200)).body.id).toBe(plan.id);

      const edited = { ...draft, generator: 'custom', name: 'My 3-day plan', workouts: draft.workouts.slice(0, 2), baseVersion: 1 };
      const v2 = (await as(alice).patch(`/api/v1/workout-plans/${plan.id}`).send(edited).expect(200)).body;
      expect(v2).toMatchObject({ id: plan.id, version: 2, name: 'My 3-day plan', source: 'custom', isActive: true });
      expect(v2.workouts).toHaveLength(2);

      const v1 = (await as(alice).get(`/api/v1/workout-plans/${plan.id}?version=1`).expect(200)).body;
      expect(v1.version).toBe(1);
      expect(v1.workouts).toHaveLength(3);
      expect((await as(alice).get(`/api/v1/workout-plans/${plan.id}`).expect(200)).body.version).toBe(2);
      await as(alice).get(`/api/v1/workout-plans/${plan.id}?version=9`).expect(404);

      const stale = await as(alice).patch(`/api/v1/workout-plans/${plan.id}`).send(edited).expect(409);
      expect(stale.body).toMatchObject({ code: 'VERSION_CONFLICT', details: { currentVersion: 2 } });
    });

    it('lets only one of two concurrent edits from the same base win', async () => {
      const draft = (await as(bob).post('/api/v1/workout-plans/generate').send(genReq).expect(200)).body;
      const plan = (await as(bob).post('/api/v1/workout-plans').send(draft).expect(201)).body;
      const results = await Promise.all([
        as(bob).patch(`/api/v1/workout-plans/${plan.id}`).send({ ...draft, name: 'Edit A', baseVersion: 1 }),
        as(bob).patch(`/api/v1/workout-plans/${plan.id}`).send({ ...draft, name: 'Edit B', baseVersion: 1 }),
      ]);
      expect(results.map((r) => r.status).sort()).toEqual([200, 409]);
      expect(await prisma.workoutPlanVersion.count({ where: { planId: plan.id } })).toBe(2);
    });

    it('activating another plan deactivates the previous one', async () => {
      const draft = (await as(bob).post('/api/v1/workout-plans/generate').send({ ...genReq, daysPerWeek: 4 }).expect(200)).body;
      const p1 = (await as(bob).post('/api/v1/workout-plans').send(draft).expect(201)).body;
      const p2 = (await as(bob).post('/api/v1/workout-plans').send({ ...draft, generator: 'custom', name: 'Second' }).expect(201)).body;
      await as(bob).post(`/api/v1/workout-plans/${p1.id}/activate`).expect(200);
      await as(bob).post(`/api/v1/workout-plans/${p2.id}/activate`).expect(200);
      expect((await as(bob).get(`/api/v1/workout-plans/${p1.id}`).expect(200)).body.isActive).toBe(false);
      expect((await as(bob).get('/api/v1/workout-plans/active').expect(200)).body.id).toBe(p2.id);
      // Activating again is harmless.
      await as(bob).post(`/api/v1/workout-plans/${p2.id}/activate`).expect(200);
    });

    it('rejects unknown exercises, bad rep ranges, duplicate order and unknown fields', async () => {
      const ex = seedId('exercise', 'push-up');
      const body = (exercises: object[], extra: object = {}) => ({ name: 'Custom', weeks: 2, generator: 'custom', workouts: [{ dayOfWeek: 2, name: 'Day', exercises }], ...extra });
      const unknown = await as(alice).post('/api/v1/workout-plans').send(body([{ exerciseId: randomUUID(), order: 0, sets: 3 }])).expect(400);
      expect(unknown.body.details.fields).toHaveProperty(['workouts.0.exercises.0.exerciseId']);
      const reps = await as(alice).post('/api/v1/workout-plans').send(body([{ exerciseId: ex, order: 0, sets: 3, repsMin: 12, repsMax: 8 }])).expect(400);
      expect(reps.body.details.fields).toHaveProperty(['workouts.0.exercises.0.repsMax']);
      await as(alice).post('/api/v1/workout-plans').send(body([{ exerciseId: ex, order: 0, sets: 3 }, { exerciseId: ex, order: 0, sets: 2 }])).expect(400);
      await as(alice).post('/api/v1/workout-plans').send(body([{ exerciseId: ex, order: 0, sets: 3 }], { xp: 100 })).expect(400);
      await as(alice).post('/api/v1/workout-plans').send(body([])).expect(400);
      const ok = (await as(alice).post('/api/v1/workout-plans').send(body([{ exerciseId: ex, order: 0, sets: 3 }])).expect(201)).body;
      expect(ok.workouts[0].exercises[0]).toMatchObject({ restSeconds: 90, repsMin: null, targetWeightKg: null });
    });

    it("hides another user's plan: 404 on read, edit and activate", async () => {
      const draft = (await as(alice).post('/api/v1/workout-plans/generate').send(genReq).expect(200)).body;
      const plan = (await as(alice).post('/api/v1/workout-plans').send(draft).expect(201)).body;
      await as(bob).get(`/api/v1/workout-plans/${plan.id}`).expect(404);
      await as(bob).patch(`/api/v1/workout-plans/${plan.id}`).send({ ...draft, baseVersion: 1 }).expect(404);
      await as(bob).post(`/api/v1/workout-plans/${plan.id}/activate`).expect(404);
      expect((await as(alice).get(`/api/v1/workout-plans/${plan.id}`).expect(200)).body.version).toBe(1);
    });

    it('a retried save with the same key creates one plan; a retried edit does not create a third version', async () => {
      const draft = (await as(alice).post('/api/v1/workout-plans/generate').send({ ...genReq, daysPerWeek: 2 }).expect(200)).body;
      const key = randomUUID();
      const a = (await as(alice).post('/api/v1/workout-plans', key).send(draft).expect(201)).body;
      const b = await as(alice).post('/api/v1/workout-plans', key).send(draft).expect(201);
      expect(b.body.id).toBe(a.id);
      expect(b.headers['idempotent-replayed']).toBe('true');

      const edit = randomUUID();
      const e1 = await as(alice).patch(`/api/v1/workout-plans/${a.id}`, edit).send({ ...draft, baseVersion: 1 }).expect(200);
      const e2 = await as(alice).patch(`/api/v1/workout-plans/${a.id}`, edit).send({ ...draft, baseVersion: 1 }).expect(200);
      expect(e2.body).toEqual(e1.body);
      expect(await prisma.workoutPlanVersion.count({ where: { planId: a.id } })).toBe(2);
      // The same key on another endpoint is rejected, never replayed.
      const other = await as(alice).post(`/api/v1/workout-plans/${a.id}/activate`, edit).expect(409);
      expect(other.body.code).toBe('IDEMPOTENCY_KEY_REUSED');
    });
  });

  it('none of these endpoints awards XP', async () => {
    expect(await prisma.xPTransaction.count({ where: { user: { email: { startsWith: 't10-' } } } })).toBe(0);
  });
});
