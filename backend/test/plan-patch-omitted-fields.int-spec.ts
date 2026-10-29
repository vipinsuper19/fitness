/**
 * Plan PATCH input rules documented on PlannedExercise / WorkoutPlanUpdate (DB_TESTS=1): only exerciseId, order and
 * sets are required; a left-out (or null) restSeconds becomes 90 and left-out notes / prescription fields become null,
 * even when the previous version had values.
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

describeDb('PATCH /workout-plans/{planId}: optional exercise fields', () => {
  let app: NestExpressApplication;
  const http = () => request(app.getHttpServer());

  beforeAll(async () => {
    app = await createTestApp();
  });
  afterAll(async () => {
    await app.close();
  });

  it('accepts omitted restSeconds, notes and prescription fields and stores 90 / null', async () => {
    const prisma = app.get(PrismaService);
    const ex = randomUUID();
    await prisma.exercise.create({
      data: { id: ex, slug: `t-${ex}`, name: `Test ${ex}`, primaryMuscle: 'chest', secondaryMuscles: [], equipment: ['none'], trackingType: 'reps_weight', difficulty: 'beginner', instructions: [] },
    });
    const reg = (await http().post('/api/v1/auth/register').send({ email: `omit-${randomUUID()}@example.invalid`, password: 'correct horse battery' }).expect(201)).body;
    const auth = { Authorization: `Bearer ${reg.accessToken as string}` };
    const full = { exerciseId: ex, order: 0, sets: 3, repsMin: 8, repsMax: 10, targetWeightKg: 40, durationSeconds: null, restSeconds: 120, notes: 'Pause at the bottom.' };
    const draft = (exercises: object[]) => ({ name: 'Omit test', weeks: 4, generator: 'custom', workouts: [{ dayOfWeek: 1, name: 'A', exercises }] });
    const plan = (await http().post('/api/v1/workout-plans').set(auth).set('Idempotency-Key', randomUUID()).send(draft([full])).expect(201)).body;

    const minimal = (await http().patch(`/api/v1/workout-plans/${plan.id}`).set(auth).set('Idempotency-Key', randomUUID())
      .send({ ...draft([{ exerciseId: ex, order: 0, sets: 2 }]), baseVersion: 1 }).expect(200)).body;
    expect(minimal.version).toBe(2);
    expect(minimal.workouts[0].exercises[0]).toEqual({
      exerciseId: ex, order: 0, sets: 2, repsMin: null, repsMax: null, targetWeightKg: null, durationSeconds: null, restSeconds: 90, notes: null,
    });

    // Explicit nulls behave the same (restSeconds: null is accepted too, although the contract types it as integer).
    const nulls = (await http().patch(`/api/v1/workout-plans/${plan.id}`).set(auth).set('Idempotency-Key', randomUUID())
      .send({ ...draft([{ ...full, repsMin: null, repsMax: null, targetWeightKg: null, restSeconds: null, notes: null }]), baseVersion: 2 }).expect(200)).body;
    expect(nulls.workouts[0].exercises[0]).toMatchObject({ repsMin: null, repsMax: null, targetWeightKg: null, restSeconds: 90, notes: null });
  });
});
