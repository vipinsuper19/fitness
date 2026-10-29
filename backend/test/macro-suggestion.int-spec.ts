/**
 * GET /me/macro-suggestion (docs/specs/macro-split-suggestion.md) over HTTP against Postgres
 * (DB_TESTS=1): missingInputs, basis always filled, goal choice, diet echo, never writes,
 * not in the export, per-user isolation, and the response validates against the contract.
 * Clock is faked (Date only).
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { NestExpressApplication } from '@nestjs/platform-express';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import request from 'supertest';
import { parse } from 'yaml';

import { seed } from '../prisma/seed';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const NOW = new Date('2026-10-29T06:30:00Z');
const DATE_ONLY: Parameters<typeof jest.useFakeTimers>[0] = {
  now: NOW,
  doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'],
};

const spec = parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: unknown };
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: 'openapi', components: spec.components });
const validate = ajv.compile({ $ref: 'openapi#/components/schemas/MacroSuggestion' });

describeDb('GET /me/macro-suggestion (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const signUp = async () => {
    const b = (await http().post('/api/v1/auth/register').send({ email: `ms-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body;
    return { token: b.accessToken as string, id: b.user.id as string };
  };
  const put = (token: string, body: object) => http().put('/api/v1/me/profile').set('Authorization', `Bearer ${token}`).send(body).expect(200);
  const suggest = async (token: string) => {
    const body = (await http().get('/api/v1/me/macro-suggestion').set('Authorization', `Bearer ${token}`).expect(200)).body;
    expect(validate(body)).toBe(true);
    expect(validate.errors ?? null).toBeNull();
    return body;
  };
  const goal = (userId: string, type: string, minutesAgo: number, status = 'active') =>
    prisma.fitnessGoal.create({ data: { userId, type: type as never, status: status as never, createdAt: new Date(NOW.getTime() - minutesAgo * 60_000) } });

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    await seed(prisma);
  });
  beforeEach(() => jest.useFakeTimers(DATE_ONLY));
  afterEach(() => jest.useRealTimers());
  afterAll(async () => {
    await app.close();
  });

  it('401 without a token', async () => {
    await http().get('/api/v1/me/macro-suggestion').expect(401);
  });

  it('missingInputs: calorie target only, weight only, both; basis always filled', async () => {
    const u = await signUp();
    let b = await suggest(u.token);
    expect(b).toEqual({
      suggestion: null,
      missingInputs: ['CALORIE_TARGET', 'WEIGHT'],
      basis: { calorieTargetKcal: null, weightKg: null, goal: 'maintain', goalSource: 'default', dietPreference: null, proteinGPerKg: 1.2, fatPct: 25 },
      formulaVersion: 1,
    });
    await put(u.token, { weightKg: 70 });
    b = await suggest(u.token);
    expect([b.suggestion, b.missingInputs, b.basis.calorieTargetKcal, b.basis.weightKg]).toEqual([null, ['CALORIE_TARGET'], null, 70]);
    await put(u.token, { weightKg: null, dailyCalorieTargetKcal: 2000 });
    b = await suggest(u.token);
    expect([b.suggestion, b.missingInputs, b.basis.calorieTargetKcal, b.basis.weightKg]).toEqual([null, ['WEIGHT'], 2000, null]);
    await put(u.token, { weightKg: 70 });
    b = await suggest(u.token);
    expect(b.missingInputs).toEqual([]);
    expect(b.suggestion).toEqual({ proteinG: 84, carbsG: 290, fatG: 56, kcal: 2000 });
  });

  it('example A via an active lose_weight goal, with diet echoed', async () => {
    const u = await signUp();
    await put(u.token, { dailyCalorieTargetKcal: 2000, weightKg: 70, dietPreference: 'vegetarian' });
    await goal(u.id, 'lose_weight', 5);
    expect(await suggest(u.token)).toEqual({
      suggestion: { proteinG: 112, carbsG: 262, fatG: 56, kcal: 2000 },
      missingInputs: [],
      basis: { calorieTargetKcal: 2000, weightKg: 70, goal: 'lose_weight', goalSource: 'active_goal', dietPreference: 'vegetarian', proteinGPerKg: 1.6, fatPct: 25 },
      formulaVersion: 1,
    });
  });

  it('whole-number maths end to end: K = 2700 and 87.5 kg endurance', async () => {
    const u = await signUp();
    await put(u.token, { dailyCalorieTargetKcal: 2700, weightKg: 180 });
    expect((await suggest(u.token)).suggestion).toEqual({ proteinG: 202, carbsG: 236, fatG: 105, kcal: 2697 });
    await put(u.token, { dailyCalorieTargetKcal: 2500, weightKg: 87.5 });
    await goal(u.id, 'improve_endurance', 1);
    expect((await suggest(u.token)).suggestion).toEqual({ proteinG: 123, carbsG: 346, fatG: 69, kcal: 2497 });
  });

  it('example F: carbs capped at 1000 g, kcal far below the target', async () => {
    const u = await signUp();
    await put(u.token, { dailyCalorieTargetKcal: 6000, weightKg: 20 });
    expect((await suggest(u.token)).suggestion).toEqual({ proteinG: 24, carbsG: 1000, fatG: 167, kcal: 5599 });
  });

  it('goal choice: newest active wins; workouts_per_week and inactive goals ignored', async () => {
    const u = await signUp();
    await put(u.token, { dailyCalorieTargetKcal: 2000, weightKg: 70 });
    await goal(u.id, 'workouts_per_week', 1);
    await goal(u.id, 'lose_weight', 2, 'achieved');
    expect((await suggest(u.token)).basis).toMatchObject({ goal: 'maintain', goalSource: 'default' });
    await goal(u.id, 'gain_muscle', 30);
    await goal(u.id, 'lose_weight', 10);
    expect((await suggest(u.token)).basis).toMatchObject({ goal: 'lose_weight', goalSource: 'active_goal', proteinGPerKg: 1.6 });
    await goal(u.id, 'improve_endurance', 3, 'abandoned');
    expect((await suggest(u.token)).basis.goal).toBe('lose_weight');
  });

  it('diet preference does not change the numbers', async () => {
    const u = await signUp();
    await put(u.token, { dailyCalorieTargetKcal: 2400, weightKg: 60, dietPreference: 'vegan' });
    const a = await suggest(u.token);
    await put(u.token, { dietPreference: 'non_vegetarian' });
    const b = await suggest(u.token);
    expect(a.suggestion).toEqual({ proteinG: 72, carbsG: 377, fatG: 67, kcal: 2399 });
    expect(b.suggestion).toEqual(a.suggestion);
    expect([a.basis.dietPreference, b.basis.dietPreference]).toEqual(['vegan', 'non_vegetarian']);
  });

  it('never writes; applying it with PUT stores exactly those grams; not in the export', async () => {
    const u = await signUp();
    await put(u.token, { dailyCalorieTargetKcal: 1500, weightKg: 90 });
    await goal(u.id, 'gain_muscle', 1);
    const before = await prisma.userProfile.findUniqueOrThrow({ where: { userId: u.id } });
    const s = (await suggest(u.token)).suggestion;
    expect(s).toEqual({ proteinG: 112, carbsG: 141, fatG: 54, kcal: 1498 });
    const after = await prisma.userProfile.findUniqueOrThrow({ where: { userId: u.id } });
    expect(after).toEqual(before);
    const p = (await put(u.token, { dailyProteinTargetG: s.proteinG, dailyCarbsTargetG: s.carbsG, dailyFatTargetG: s.fatG })).body;
    expect([p.dailyProteinTargetG, p.dailyCarbsTargetG, p.dailyFatTargetG]).toEqual([112, 141, 54]);
    const ex = (await http().get('/api/v1/me/export').set('Authorization', `Bearer ${u.token}`).expect(200)).body;
    expect(JSON.stringify(ex)).not.toMatch(/macroSuggestion|missingInputs|formulaVersion/);
  });

  it("another user's profile and goals never affect the result", async () => {
    const a = await signUp();
    const b = await signUp();
    await put(a.token, { dailyCalorieTargetKcal: 1200, weightKg: 80 });
    await put(b.token, { dailyCalorieTargetKcal: 3000, weightKg: 100 });
    await goal(b.id, 'gain_muscle', 1);
    const r = await suggest(a.token);
    expect(r.suggestion).toEqual({ proteinG: 90, carbsG: 106, fatG: 46, kcal: 1198 });
    expect(r.basis.goalSource).toBe('default');
  });
});
