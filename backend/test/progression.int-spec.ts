/**
 * GET /workout-plans/{planId}/progression-suggestions (docs/specs/adaptive-progression.md)
 * over HTTP against Postgres (DB_TESTS=1). Clock faked (Date only) at 2026-10-29 12:00 IST;
 * sessions are inserted with fixed completedAt values before that.
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { parse } from 'yaml';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { seed } from '../prisma/seed';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const NOW = new Date('2026-10-29T06:30:00Z');
const DAY = 86_400_000;
const DATE_ONLY: Parameters<typeof jest.useFakeTimers>[0] = {
  now: NOW,
  doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'],
};

type Muscle = 'chest' | 'back' | 'quads' | 'core' | 'cardio' | 'biceps' | 'forearms';
type Tracking = 'reps_weight' | 'reps' | 'duration' | 'distance_duration';
type Level = 'beginner' | 'intermediate' | 'advanced';
type Equip = 'none' | 'dumbbell' | 'barbell';
type PE = { ex: string; sets?: number; repsMin?: number | null; repsMax?: number | null; targetWeightKg?: number | null; durationSeconds?: number | null };
type SetIn = { reps?: number | null; kg?: number | null; sec?: number | null; m?: number | null; completed?: boolean };
type Sugg = { exerciseId: string; action: string; reason: string; reasonText: string; current: Record<string, unknown>; suggested: Record<string, unknown> | null; harderVariations?: string[]; cooldownUntil: string | null; basedOn: Record<string, unknown>[]; order: number; plannedWorkoutId: string; trackingType: string };

describeDb('progression suggestions (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const as = (token: string) => ({
    get: (url: string) => http().get(url).set('Authorization', `Bearer ${token}`),
    post: (url: string) => http().post(url).set('Authorization', `Bearer ${token}`).set('Idempotency-Key', randomUUID()),
    patch: (url: string) => http().patch(url).set('Authorization', `Bearer ${token}`).set('Idempotency-Key', randomUUID()),
    delete: (url: string) => http().delete(url).set('Authorization', `Bearer ${token}`).set('Idempotency-Key', randomUUID()),
  });
  const signUp = async () => {
    const b = (await http().post('/api/v1/auth/register').send({ email: `prog-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body;
    return { token: b.accessToken as string, id: b.user.id as string };
  };
  const mkEx = async (o: { muscle?: Muscle; tracking?: Tracking; level?: Level; equipment?: Equip[]; name?: string } = {}) => {
    const id = randomUUID();
    await prisma.exercise.create({
      data: {
        id, slug: `t-${id}`, name: o.name ?? `Test ${id}`, primaryMuscle: o.muscle ?? 'chest', secondaryMuscles: [], equipment: o.equipment ?? ['none'],
        trackingType: o.tracking ?? 'reps_weight', difficulty: o.level ?? 'beginner', instructions: [],
      },
    });
    return id;
  };
  const draftOf = (pes: PE[]) => ({
    name: 'Progression test', weeks: 4, generator: 'custom',
    workouts: [{
      dayOfWeek: 1, name: 'A',
      exercises: pes.map((e, i) => ({
        exerciseId: e.ex, order: i, sets: e.sets ?? 2, repsMin: e.repsMin ?? null, repsMax: e.repsMax ?? null,
        targetWeightKg: e.targetWeightKg ?? null, durationSeconds: e.durationSeconds ?? null, restSeconds: 90,
      })),
    }],
  });
  const mkPlan = async (token: string, pes: PE[]) => {
    const body = (await as(token).post('/api/v1/workout-plans').send(draftOf(pes)).expect(201)).body as { id: string };
    return body.id;
  };
  const version = async (planId: string, v = 1) => {
    const row = await prisma.workoutPlanVersion.findUniqueOrThrow({ where: { planId_version: { planId, version: v } }, include: { workouts: true } });
    return { versionId: row.id, pwId: row.workouts[0]!.id };
  };
  /** A completed session following the plan version, with sets for one or more exercises. */
  const session = async (userId: string, planId: string, daysAgo: number, sets: Record<string, SetIn[]>, o: { difficulty?: number | null; v?: number; status?: 'completed' | 'in_progress' } = {}) => {
    const { versionId, pwId } = await version(planId, o.v ?? 1);
    const at = new Date(NOW.getTime() - daysAgo * DAY);
    const status = o.status ?? 'completed';
    const s = await prisma.workoutSession.create({
      data: {
        userId, planVersionId: versionId, plannedWorkoutId: pwId, status, startedAt: new Date(at.getTime() - 1_800_000),
        completedAt: status === 'completed' ? at : null, activeSince: status === 'in_progress' ? at : null, activeSeconds: 1800,
        perceivedDifficulty: o.difficulty === undefined ? 3 : o.difficulty,
      },
    });
    let i = 0;
    for (const [ex, list] of Object.entries(sets)) {
      await prisma.exerciseSetLog.createMany({
        data: list.map((x) => ({
          userId, sessionId: s.id, clientSetId: randomUUID(), exerciseId: ex, setIndex: i++, reps: x.reps ?? null, weightKg: x.kg ?? null,
          durationSeconds: x.sec ?? null, distanceM: x.m ?? null, completed: x.completed ?? true,
        })),
      });
    }
    return s.id;
  };
  const three = async (userId: string, planId: string, sets: Record<string, SetIn[]>, difficulty: number | null = 3) => {
    for (const d of [6, 4, 2]) await session(userId, planId, d, sets, { difficulty });
  };
  const rep = (n: number, s: SetIn) => Array.from({ length: n }, () => s);
  const suggest = async (token: string, planId: string) => (await as(token).get(`/api/v1/workout-plans/${planId}/progression-suggestions`).expect(200)).body as {
    planId: string; planVersion: number; generatedAt: string; windowSessions: number; suggestions: Sugg[];
  };
  const byEx = (b: { suggestions: Sugg[] }, ex: string) => b.suggestions.find((s) => s.exerciseId === ex)!;

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

  it('auth guard; another user\'s plan or unknown id is 404; archived plan is 409 PLAN_ARCHIVED', async () => {
    const u = await signUp();
    const other = await signUp();
    const plan = await mkPlan(u.token, [{ ex: await mkEx(), repsMax: 10, targetWeightKg: 40 }]);
    await http().get(`/api/v1/workout-plans/${plan}/progression-suggestions`).expect(401);
    await as(other.token).get(`/api/v1/workout-plans/${plan}/progression-suggestions`).expect(404);
    await as(u.token).get(`/api/v1/workout-plans/${randomUUID()}/progression-suggestions`).expect(404);
    await as(u.token).delete(`/api/v1/workout-plans/${plan}`).expect(204);
    const r = await as(u.token).get(`/api/v1/workout-plans/${plan}/progression-suggestions`).expect(409);
    expect(r.body.code).toBe('PLAN_ARCHIVED');
  });

  it('empty history and fewer than 3 sessions: hold / NOT_ENOUGH_HISTORY, response shape, plan order', async () => {
    const u = await signUp();
    const a = await mkEx();
    const b = await mkEx({ tracking: 'duration', muscle: 'core' });
    const plan = await mkPlan(u.token, [{ ex: a, sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: 40 }, { ex: b, sets: 1, durationSeconds: 60 }]);
    let s = await suggest(u.token, plan);
    expect(s).toMatchObject({ planId: plan, planVersion: 1, windowSessions: 3, generatedAt: NOW.toISOString() });
    expect(s.suggestions.map((x) => [x.exerciseId, x.order, x.trackingType])).toEqual([[a, 0, 'reps_weight'], [b, 1, 'duration']]);
    expect(s.suggestions[0]).toMatchObject({
      action: 'hold', reason: 'NOT_ENOUGH_HISTORY', suggested: null, cooldownUntil: null, basedOn: [],
      current: { sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: 40, durationSeconds: null },
    });
    expect(typeof s.suggestions[0]!.reasonText).toBe('string');
    expect(s.suggestions[0]!.harderVariations).toBeUndefined();
    await session(u.id, plan, 3, { [a]: rep(2, { reps: 10, kg: 40 }) });
    await session(u.id, plan, 2, { [a]: rep(2, { reps: 10, kg: 40 }) });
    s = await suggest(u.token, plan);
    expect(byEx(s, a)).toMatchObject({ action: 'hold', reason: 'NOT_ENOUGH_HISTORY' });
    expect(byEx(s, a).basedOn).toHaveLength(2);
    expect(byEx(s, a).basedOn[0]).toMatchObject({ completedSets: 2, plannedSets: 2, metTarget: true, bestReps: 10, bestWeightKg: 40, bestDurationSeconds: null, bestDistanceM: null, perceivedDifficulty: 3 });
  });

  it('weight increments: +2.5% upper, +5% lower, 0.5 kg floor/rounding, NO_TARGET_SET; complete suggested values', async () => {
    const u = await signUp();
    const upper = await mkEx({ muscle: 'chest' });
    const lower = await mkEx({ muscle: 'quads' });
    const small = await mkEx({ muscle: 'biceps' });
    const noTarget = await mkEx({ muscle: 'back' });
    const plan = await mkPlan(u.token, [
      { ex: upper, repsMin: 8, repsMax: 10, targetWeightKg: 40 },
      { ex: lower, repsMin: 5, repsMax: 5, targetWeightKg: 100 },
      { ex: small, repsMin: 10, repsMax: 12, targetWeightKg: 8 },
      { ex: noTarget, repsMin: 8, repsMax: 8 },
    ]);
    await three(u.id, plan, {
      [upper]: rep(2, { reps: 10, kg: 40 }), [lower]: rep(2, { reps: 5, kg: 100 }), [small]: rep(2, { reps: 12, kg: 8 }),
      [noTarget]: [{ reps: 8, kg: 35 }, { reps: 8, kg: 37.3 }],
    });
    const s = await suggest(u.token, plan);
    expect(byEx(s, upper)).toMatchObject({ action: 'increase', reason: 'ALL_SETS_COMPLETED', suggested: { sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: 41, durationSeconds: null } });
    expect(byEx(s, lower).suggested).toEqual({ sets: 2, repsMin: 5, repsMax: 5, targetWeightKg: 105, durationSeconds: null });
    expect(byEx(s, small).suggested!.targetWeightKg).toBe(8.5); // min step 0.5 kg beats 2.5% and the cap
    expect(byEx(s, noTarget)).toMatchObject({ action: 'increase', reason: 'NO_TARGET_SET', suggested: { targetWeightKg: 37, repsMin: 8, repsMax: 8 } });
    for (const x of s.suggestions) expect(((x.suggested!.targetWeightKg as number) * 2) % 1).toBe(0);
    // Off-grid targets never undercut the min step.
    const off = await mkEx({ muscle: 'chest' });
    const tiny = await mkEx({ muscle: 'chest' });
    const plan2 = await mkPlan(u.token, [{ ex: off, repsMax: 10, targetWeightKg: 40.3 }, { ex: tiny, repsMax: 10, targetWeightKg: 9.8 }]);
    await three(u.id, plan2, { [off]: rep(2, { reps: 10, kg: 40.5 }), [tiny]: rep(2, { reps: 10, kg: 10 }) });
    const s2 = await suggest(u.token, plan2);
    expect(byEx(s2, off).suggested!.targetWeightKg).toBe(41.5);
    expect(byEx(s2, tiny).suggested!.targetWeightKg).toBe(10.5);
    expect(byEx(s, upper).basedOn).toHaveLength(3);
  });

  it('reps: +1, +2 from 15 (exempt from the cap), REP_CEILING with rule-based harderVariations in name/id order', async () => {
    const u = await signUp();
    const tag = randomUUID().slice(0, 6);
    const base = await mkEx({ tracking: 'reps', muscle: 'forearms', level: 'beginner', equipment: ['none'], name: `!pv ${tag} base` });
    // Candidates (intermediate, forearms, reps, compatible equipment): names sort before the catalogue.
    const c1 = await mkEx({ tracking: 'reps', muscle: 'forearms', level: 'intermediate', name: `!pv ${tag} a` });
    const c2 = await mkEx({ tracking: 'reps', muscle: 'forearms', level: 'intermediate', name: `!pv ${tag} a` });
    const c3 = await mkEx({ tracking: 'reps', muscle: 'forearms', level: 'intermediate', name: `!pv ${tag} b` });
    await mkEx({ tracking: 'reps', muscle: 'forearms', level: 'intermediate', name: `!pv ${tag} c` }); // 4th: beyond the 3 limit
    await mkEx({ tracking: 'reps', muscle: 'forearms', level: 'intermediate', equipment: ['barbell'], name: `!pv ${tag} 0` }); // equipment not used
    await mkEx({ tracking: 'reps', muscle: 'forearms', level: 'advanced', name: `!pv ${tag} 1` }); // two levels up
    const adv = await mkEx({ tracking: 'reps', muscle: 'forearms', level: 'advanced', name: `!pv ${tag} adv` });
    const a = await mkEx({ tracking: 'reps', muscle: 'core' });
    const b = await mkEx({ tracking: 'reps', muscle: 'core' });
    const small = await mkEx({ tracking: 'reps', muscle: 'core' });
    const plan = await mkPlan(u.token, [
      { ex: a, repsMin: 8, repsMax: 12 }, { ex: b, repsMin: 15, repsMax: 18 }, { ex: small, repsMin: 3, repsMax: 5 },
      { ex: base, repsMin: 19, repsMax: 20 }, { ex: adv, repsMin: 19, repsMax: 20 },
    ]);
    await three(u.id, plan, { [a]: rep(2, { reps: 12 }), [b]: rep(2, { reps: 18 }), [small]: rep(2, { reps: 5 }), [base]: rep(2, { reps: 20 }), [adv]: rep(2, { reps: 20 }) });
    const s = await suggest(u.token, plan);
    expect(byEx(s, a).suggested).toMatchObject({ repsMin: 9, repsMax: 13 });
    expect(byEx(s, b).suggested).toMatchObject({ repsMin: 17, repsMax: 20 });
    expect(byEx(s, small).suggested).toMatchObject({ repsMin: 4, repsMax: 6 });
    const ids = [c1, c2].sort();
    expect(byEx(s, base)).toMatchObject({ action: 'hold', reason: 'REP_CEILING', suggested: null, harderVariations: [ids[0], ids[1], c3] });
    expect(byEx(s, adv)).toMatchObject({ reason: 'REP_CEILING', harderVariations: [] });
    expect(byEx(s, a).harderVariations).toBeUndefined();
  });

  it('duration and distance_duration: +10% down to 5 s, min +5 s; CAP_REACHED at limits', async () => {
    const u = await signUp();
    const d60 = await mkEx({ tracking: 'duration', muscle: 'core' });
    const d300 = await mkEx({ tracking: 'distance_duration', muscle: 'cardio' });
    const d30 = await mkEx({ tracking: 'duration', muscle: 'core' });
    const dMax = await mkEx({ tracking: 'duration', muscle: 'core' });
    const wMax = await mkEx({ muscle: 'quads' });
    const plan = await mkPlan(u.token, [
      { ex: d60, sets: 1, durationSeconds: 60 }, { ex: d300, sets: 1, durationSeconds: 300 }, { ex: d30, sets: 1, durationSeconds: 30 },
      { ex: dMax, sets: 1, durationSeconds: 7200 }, { ex: wMax, sets: 1, repsMax: 5, targetWeightKg: 1000 },
    ]);
    await three(u.id, plan, {
      [d60]: [{ sec: 60 }], [d300]: [{ sec: 310, m: 1000.5 }], [d30]: [{ sec: 30 }], [dMax]: [{ sec: 7200 }], [wMax]: [{ reps: 5, kg: 1000 }],
    });
    const s = await suggest(u.token, plan);
    expect(byEx(s, d60).suggested!.durationSeconds).toBe(65);
    expect(byEx(s, d300).suggested!.durationSeconds).toBe(330);
    expect(byEx(s, d300).basedOn[0]).toMatchObject({ bestDurationSeconds: 310, bestDistanceM: 1000.5 });
    expect(byEx(s, d30).suggested!.durationSeconds).toBe(35);
    expect(byEx(s, dMax)).toMatchObject({ action: 'hold', reason: 'CAP_REACHED', suggested: null });
    expect(byEx(s, wMax)).toMatchObject({ action: 'hold', reason: 'CAP_REACHED' });
  });

  it('MISSED_TARGET and HIGH_EFFORT; incomplete sets, in-progress sessions and other users\' sessions are ignored', async () => {
    const u = await signUp();
    const missed = await mkEx();
    const effort = await mkEx();
    const plan = await mkPlan(u.token, [{ ex: missed, repsMax: 10, targetWeightKg: 40 }, { ex: effort, repsMax: 10, targetWeightKg: 40 }]);
    await session(u.id, plan, 6, { [missed]: rep(2, { reps: 10, kg: 40 }), [effort]: rep(2, { reps: 10, kg: 40 }) });
    await session(u.id, plan, 4, { [missed]: [{ reps: 10, kg: 40 }, { reps: 9, kg: 40 }], [effort]: rep(2, { reps: 10, kg: 40 }) }, { difficulty: 4 });
    // An extra incomplete set with low reps does not count against the target.
    await session(u.id, plan, 2, { [missed]: [...rep(2, { reps: 10, kg: 40 }), { reps: 1, kg: 40, completed: false }], [effort]: rep(2, { reps: 10, kg: 40 }) });
    let s = await suggest(u.token, plan);
    expect(byEx(s, missed)).toMatchObject({ action: 'hold', reason: 'MISSED_TARGET', suggested: null });
    expect(byEx(s, missed).basedOn.map((x) => x.metTarget)).toEqual([true, false, true]);
    expect(byEx(s, effort)).toMatchObject({ action: 'hold', reason: 'HIGH_EFFORT' });
    // In-progress session and another user's sessions on this plan version do not enter the window.
    await session(u.id, plan, 1, { [effort]: rep(2, { reps: 1, kg: 1 }) }, { status: 'in_progress' });
    const other = await signUp();
    for (const d of [1.5, 1.2, 1.1]) await session(other.id, plan, d, { [effort]: rep(2, { reps: 10, kg: 40 }) });
    s = await suggest(u.token, plan);
    expect(byEx(s, effort).basedOn).toHaveLength(3);
    expect(byEx(s, effort).reason).toBe('HIGH_EFFORT');
  });

  it('deload: two consecutive misses (weight -10% to 0.5 kg; duration 65 s -> 55 s) and two sessions at difficulty 5', async () => {
    const u = await signUp();
    const w = await mkEx();
    const d = await mkEx({ tracking: 'duration', muscle: 'core' });
    const hard = await mkEx();
    const repsOnly = await mkEx({ tracking: 'reps', muscle: 'core' });
    const plan = await mkPlan(u.token, [
      { ex: w, repsMax: 10, targetWeightKg: 41 }, { ex: d, sets: 1, durationSeconds: 65 }, { ex: hard, repsMax: 10, targetWeightKg: 50 },
      { ex: repsOnly, repsMin: 8, repsMax: 10 },
    ]);
    await session(u.id, plan, 3, { [w]: rep(2, { reps: 8, kg: 41 }), [d]: [{ sec: 50 }], [hard]: rep(2, { reps: 10, kg: 50 }), [repsOnly]: rep(2, { reps: 6 }) }, { difficulty: 5 });
    await session(u.id, plan, 1, { [w]: rep(2, { reps: 8, kg: 41 }), [d]: [{ sec: 50 }], [hard]: rep(2, { reps: 10, kg: 50 }), [repsOnly]: rep(2, { reps: 6 }) }, { difficulty: 5 });
    const s = await suggest(u.token, plan);
    expect(byEx(s, w)).toMatchObject({ action: 'deload', reason: 'REPEATED_MISSES', suggested: { targetWeightKg: 36.5, repsMax: 10, sets: 2 } });
    expect(byEx(s, d)).toMatchObject({ action: 'deload', reason: 'REPEATED_MISSES', suggested: { durationSeconds: 55 } });
    expect(byEx(s, hard)).toMatchObject({ action: 'deload', reason: 'VERY_HARD', suggested: { targetWeightKg: 45 } });
    // No load to lower on a reps-only exercise: hold with the deload reason.
    expect(byEx(s, repsOnly)).toMatchObject({ action: 'hold', reason: 'REPEATED_MISSES', suggested: null });
  });

  it('cooldown from plan versions (3 days -> COOLDOWN with cooldownUntil; 8 days -> increase); accept two in one PATCH; read-only', async () => {
    const u = await signUp();
    const a = await mkEx();
    const b = await mkEx({ tracking: 'duration', muscle: 'core' });
    const plan = await mkPlan(u.token, [{ ex: a, repsMax: 10, targetWeightKg: 40 }, { ex: b, sets: 1, durationSeconds: 60 }]);
    await three(u.id, plan, { [a]: rep(2, { reps: 10, kg: 41 }), [b]: [{ sec: 70 }] });

    const s1 = await suggest(u.token, plan);
    expect((await prisma.workoutPlan.findUniqueOrThrow({ where: { id: plan } })).currentVersion).toBe(1); // nothing written
    expect(byEx(s1, a).suggested!.targetWeightKg).toBe(41);
    expect(byEx(s1, b).suggested!.durationSeconds).toBe(65);

    // Accept both in one PATCH built from `suggested` and the top-level planVersion.
    const pes = s1.suggestions.map((x) => ({ ex: x.exerciseId, ...(x.suggested as object) }));
    const patched = (await as(u.token).patch(`/api/v1/workout-plans/${plan}`).send({ ...draftOf(pes), baseVersion: s1.planVersion }).expect(200)).body;
    expect(patched.version).toBe(2);
    expect(patched.workouts[0].exercises.map((e: { targetWeightKg: number | null; durationSeconds: number | null }) => [e.targetWeightKg, e.durationSeconds])).toEqual([[41, null], [null, 65]]);
    // A stale suggestion (old planVersion) is rejected by the existing PATCH.
    const stale = await as(u.token).patch(`/api/v1/workout-plans/${plan}`).send({ ...draftOf(pes), baseVersion: s1.planVersion }).expect(409);
    expect(stale.body.code).toBe('VERSION_CONFLICT');

    const v2 = await prisma.workoutPlanVersion.findUniqueOrThrow({ where: { planId_version: { planId: plan, version: 2 } } });
    const raisedAt = new Date(NOW.getTime() - 3 * DAY);
    await prisma.workoutPlanVersion.update({ where: { id: v2.id }, data: { createdAt: raisedAt } });
    // Three sessions on v2 meeting the new prescription.
    for (const dd of [2.5, 2, 1]) await session(u.id, plan, dd, { [a]: rep(2, { reps: 10, kg: 41 }), [b]: [{ sec: 65 }] }, { v: 2 });
    let s = await suggest(u.token, plan);
    expect(s.planVersion).toBe(2);
    expect(byEx(s, a)).toMatchObject({ action: 'hold', reason: 'COOLDOWN', suggested: null, cooldownUntil: new Date(raisedAt.getTime() + 7 * DAY).toISOString() });
    expect(byEx(s, b).reason).toBe('COOLDOWN');

    await prisma.workoutPlanVersion.update({ where: { id: v2.id }, data: { createdAt: new Date(NOW.getTime() - 8 * DAY) } });
    s = await suggest(u.token, plan);
    expect(byEx(s, a)).toMatchObject({ action: 'increase', reason: 'ALL_SETS_COMPLETED', cooldownUntil: null, suggested: { targetWeightKg: 42 } });
    expect(byEx(s, b).suggested!.durationSeconds).toBe(70);
  });

  it('corrections apply: a set corrected through the session-correction API changes the suggestion', async () => {
    const u = await signUp();
    const a = await mkEx();
    const plan = await mkPlan(u.token, [{ ex: a, repsMin: 8, repsMax: 10, targetWeightKg: 40 }]);
    await session(u.id, plan, 4, { [a]: rep(2, { reps: 10, kg: 40 }) });
    await session(u.id, plan, 2, { [a]: rep(2, { reps: 10, kg: 40 }) });
    // Third session through the API (today, inside the correction window): one set short of repsMax.
    const { pwId } = await version(plan);
    const sid = (await as(u.token).post('/api/v1/workout-sessions').send({ plannedWorkoutId: pwId, planVersion: 1 }).expect(201)).body.id as string;
    const ids = [randomUUID(), randomUUID()];
    const setBody = (i: number, reps: number) => ({ clientSetId: ids[i], exerciseId: a, setIndex: i, reps, weightKg: 40, completed: true });
    await as(u.token).post(`/api/v1/workout-sessions/${sid}/sets`).send(setBody(0, 10)).expect(200);
    await as(u.token).post(`/api/v1/workout-sessions/${sid}/sets`).send(setBody(1, 9)).expect(200);
    await as(u.token).post(`/api/v1/workout-sessions/${sid}/complete`).send({ perceivedDifficulty: 3 }).expect(200);
    let s = await suggest(u.token, plan);
    expect(byEx(s, a)).toMatchObject({ action: 'hold', reason: 'MISSED_TARGET' });
    expect(byEx(s, a).basedOn[0]).toMatchObject({ sessionId: sid, metTarget: false });

    const put = http().put(`/api/v1/workout-sessions/${sid}/corrections/sets/${ids[1]}`).set('Authorization', `Bearer ${u.token}`).set('Idempotency-Key', randomUUID());
    await put.send(setBody(1, 10)).expect(200);
    s = await suggest(u.token, plan);
    expect(byEx(s, a).basedOn[0]).toMatchObject({ sessionId: sid, metTarget: true, bestReps: 10 });
    expect(byEx(s, a)).toMatchObject({ action: 'increase', reason: 'ALL_SETS_COMPLETED', suggested: { targetWeightKg: 41 } });
  });

  it('cooldown compares by entry: same exercise in two workouts, only one raised', async () => {
    const u = await signUp();
    const a = await mkEx();
    const two = (wA: number, wB: number) => ({
      name: 'Two workouts', weeks: 4, generator: 'custom',
      workouts: [
        { dayOfWeek: 1, name: 'A', exercises: [{ exerciseId: a, order: 0, sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: wA, durationSeconds: null, restSeconds: 90 }] },
        { dayOfWeek: 3, name: 'B', exercises: [{ exerciseId: a, order: 0, sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: wB, durationSeconds: null, restSeconds: 90 }] },
      ],
    });
    const plan = (await as(u.token).post('/api/v1/workout-plans').send(two(40, 30)).expect(201)).body.id as string;
    // v2 raises only workout B (30 -> 31); workout A is unchanged.
    await as(u.token).patch(`/api/v1/workout-plans/${plan}`).send({ ...two(40, 31), baseVersion: 1 }).expect(200);
    const v2 = await prisma.workoutPlanVersion.findUniqueOrThrow({ where: { planId_version: { planId: plan, version: 2 } } });
    await prisma.workoutPlanVersion.update({ where: { id: v2.id }, data: { createdAt: new Date(NOW.getTime() - 2 * DAY) } });
    for (const d of [1.5, 1.2, 1]) await session(u.id, plan, d, { [a]: rep(2, { reps: 10, kg: 41 }) }, { v: 2 });
    const s = await suggest(u.token, plan);
    expect(s.suggestions).toHaveLength(2);
    const [first, second] = s.suggestions;
    expect(first).toMatchObject({ exerciseId: a, action: 'increase', reason: 'ALL_SETS_COMPLETED', cooldownUntil: null, suggested: { targetWeightKg: 41 } });
    expect(second).toMatchObject({ exerciseId: a, action: 'hold', reason: 'COOLDOWN', suggested: null });
    expect(first!.plannedWorkoutId).not.toBe(second!.plannedWorkoutId);
    expect(first!.basedOn.map((x) => x.sessionId)).toEqual(second!.basedOn.map((x) => x.sessionId)); // shared history
  });

  it('cooldown survives reordering workouts and exercises between versions', async () => {
    const u = await signUp();
    const a = await mkEx();
    const b = await mkEx();
    const wk = (name: string, day: number, exs: [string, number][]) => ({
      dayOfWeek: day, name, exercises: exs.map(([id, kg], i) => ({ exerciseId: id, order: i, sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: kg, durationSeconds: null, restSeconds: 90 })),
    });
    const body = (workouts: object[]) => ({ name: 'Reorder', weeks: 4, generator: 'custom', workouts });
    const plan = (await as(u.token).post('/api/v1/workout-plans').send(body([wk('Push', 1, [[a, 40], [b, 20]]), wk('Pull', 3, [[a, 30]])])).expect(201)).body.id as string;
    // v2: workouts swapped, exercises in Push reordered, and only Push's `b` raised (20 -> 21).
    await as(u.token).patch(`/api/v1/workout-plans/${plan}`).send({ ...body([wk('Pull', 3, [[a, 30]]), wk('Push', 1, [[b, 21], [a, 40]])]), baseVersion: 1 }).expect(200);
    const v2 = await prisma.workoutPlanVersion.findUniqueOrThrow({ where: { planId_version: { planId: plan, version: 2 } } });
    await prisma.workoutPlanVersion.update({ where: { id: v2.id }, data: { createdAt: new Date(NOW.getTime() - 2 * DAY) } });
    for (const d of [1.5, 1.2, 1]) await session(u.id, plan, d, { [a]: rep(2, { reps: 10, kg: 41 }), [b]: rep(2, { reps: 10, kg: 21 }) }, { v: 2 });
    const s = await suggest(u.token, plan);
    expect(s.suggestions.map((x) => [x.exerciseId, x.reason])).toEqual([
      [a, 'ALL_SETS_COMPLETED'], // Pull a: unchanged
      [b, 'COOLDOWN'], // Push b: raised in v2 despite the reorder
      [a, 'ALL_SETS_COMPLETED'], // Push a: unchanged, moved from position 0 to 1
    ]);
  });

  const wkOf = (name: string, day: number, exs: [string, number][]) => ({
    dayOfWeek: day, name, exercises: exs.map(([id, kg], i) => ({ exerciseId: id, order: i, sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: kg, durationSeconds: null, restSeconds: 90 })),
  });
  const twoVersions = async (v1: object[], v2: object[]) => {
    const u = await signUp();
    const body = (workouts: object[]) => ({ name: 'Match', weeks: 4, generator: 'custom', workouts });
    const plan = (await as(u.token).post('/api/v1/workout-plans').send(body(v1)).expect(201)).body.id as string;
    await as(u.token).patch(`/api/v1/workout-plans/${plan}`).send({ ...body(v2), baseVersion: 1 }).expect(200);
    const v = await prisma.workoutPlanVersion.findUniqueOrThrow({ where: { planId_version: { planId: plan, version: 2 } } });
    await prisma.workoutPlanVersion.update({ where: { id: v.id }, data: { createdAt: new Date(NOW.getTime() - 2 * DAY) } });
    return { u, plan };
  };

  it('cooldown matches the k-th occurrence of a repeated exercise to the k-th', async () => {
    const a = await mkEx();
    const b = await mkEx();
    const { u, plan } = await twoVersions([wkOf('Push', 1, [[a, 40], [b, 20], [a, 30]])], [wkOf('Push', 1, [[a, 35], [a, 32.5], [b, 20]])]);
    for (const d of [1.5, 1.2, 1]) await session(u.id, plan, d, { [a]: rep(2, { reps: 10, kg: 43 }), [b]: rep(2, { reps: 10, kg: 20 }) }, { v: 2 });
    const s = await suggest(u.token, plan);
    expect(s.suggestions.map((x) => [x.exerciseId, x.reason === 'COOLDOWN'])).toEqual([[a, false], [a, true], [b, false]]);
  });

  it('an added workout does not inherit the raise of a workout matched by day and name', async () => {
    const a = await mkEx();
    // v2 inserts Legs at order 0 (where Push was); Push moves to order 1 and is matched by day+name.
    const { u, plan } = await twoVersions([wkOf('Push', 1, [[a, 40]])], [wkOf('Legs', 2, [[a, 45]]), wkOf('Push', 1, [[a, 40]])]);
    for (const d of [1.5, 1.2, 1]) await session(u.id, plan, d, { [a]: rep(2, { reps: 10, kg: 45 }) }, { v: 2 });
    const s = await suggest(u.token, plan);
    expect(s.suggestions.map((x) => x.reason === 'COOLDOWN')).toEqual([false, false]);
  });

  it('Android cases: paused and inactive plans get suggestions; archived is 409; unarchived works again', async () => {
    const u = await signUp();
    const a = await mkEx();
    const paused = await mkPlan(u.token, [{ ex: a, repsMax: 10, targetWeightKg: 40 }]);
    const inactive = await mkPlan(u.token, [{ ex: a, repsMax: 10, targetWeightKg: 40 }]);
    await as(u.token).post(`/api/v1/workout-plans/${paused}/activate`).send({}).expect(200);
    await as(u.token).post(`/api/v1/workout-plans/${paused}/deactivate`).expect(204);
    const statuses = (await as(u.token).get('/api/v1/workout-plans').expect(200)).body.items as { id: string; status: string }[];
    expect(statuses.find((p) => p.id === paused)!.status).toBe('paused');
    expect(statuses.find((p) => p.id === inactive)!.status).toBe('inactive');
    await three(u.id, paused, { [a]: rep(2, { reps: 10, kg: 40 }) });
    expect(byEx(await suggest(u.token, paused), a)).toMatchObject({ action: 'increase', reason: 'ALL_SETS_COMPLETED' });
    const none = await suggest(u.token, inactive);
    expect(none.suggestions).toHaveLength(1);
    expect(none.suggestions[0]).toMatchObject({ action: 'hold', reason: 'NOT_ENOUGH_HISTORY', suggested: null, cooldownUntil: null, basedOn: [] });

    await as(u.token).delete(`/api/v1/workout-plans/${paused}`).expect(204);
    expect((await as(u.token).get(`/api/v1/workout-plans/${paused}/progression-suggestions`).expect(409)).body.code).toBe('PLAN_ARCHIVED');
    await as(u.token).post(`/api/v1/workout-plans/${paused}/unarchive`).send({}).expect(200);
    await suggest(u.token, paused);
    // A malformed id is 404 like an unknown one.
    expect((await as(u.token).get('/api/v1/workout-plans/not-a-uuid/progression-suggestions').expect(404)).body.code).toBe('NOT_FOUND');
  });

  it('applying with Idempotency-Key: replay returns the stored version (no second version, no VERSION_CONFLICT); other key is 409 with details.currentVersion', async () => {
    const u = await signUp();
    const a = await mkEx();
    const plan = await mkPlan(u.token, [{ ex: a, repsMax: 10, targetWeightKg: 40 }]);
    await three(u.id, plan, { [a]: rep(2, { reps: 10, kg: 40 }) });
    const s = await suggest(u.token, plan);
    const body = { ...draftOf(s.suggestions.map((x) => ({ ex: x.exerciseId, ...(x.suggested as object) }))), baseVersion: s.planVersion };
    const key = randomUUID();
    const patch = (k: string) => http().patch(`/api/v1/workout-plans/${plan}`).set('Authorization', `Bearer ${u.token}`).set('Idempotency-Key', k);
    const first = await patch(key).send(body).expect(200);
    expect(first.body.version).toBe(2);
    expect(first.body.workouts[0].exercises[0].targetWeightKg).toBe(41);
    const replay = await patch(key).send(body).expect(200);
    expect(replay.headers['idempotent-replayed']).toBe('true');
    expect(replay.body).toEqual(first.body);
    expect((await prisma.workoutPlan.findUniqueOrThrow({ where: { id: plan } })).currentVersion).toBe(2);
    const reused = await patch(key).send({ ...body, weeks: 5 }).expect(409);
    expect(reused.body.code).toBe('IDEMPOTENCY_KEY_REUSED');
    const stale = await patch(randomUUID()).send(body).expect(409);
    expect(stale.body).toMatchObject({ code: 'VERSION_CONFLICT', details: { currentVersion: 2 } });
  });

  it('inputs: populated per ProgressionInputs and the GET body validates against the openapi schema', async () => {
    const openapi = parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: { schemas: unknown } };
    const ajv = new Ajv2020({ allErrors: true, strict: false });
    addFormats(ajv);
    const defs = JSON.parse(JSON.stringify(openapi.components.schemas).replaceAll('#/components/schemas/', '#/$defs/'));
    const validate = ajv.compile({ $defs: defs, $ref: '#/$defs/ProgressionSuggestions' });
    const check = (body: unknown) => expect({ ok: validate(body), errors: validate.errors }).toEqual({ ok: true, errors: null });

    const u = await signUp();
    const a = await mkEx();
    const b = await mkEx({ tracking: 'duration', muscle: 'core' });
    const plan = await mkPlan(u.token, [{ ex: a, repsMax: 10, targetWeightKg: 20 }, { ex: b, sets: 1, durationSeconds: 60 }]);

    // No history.
    let s = await suggest(u.token, plan);
    check(s);
    expect((byEx(s, a) as unknown as { inputs: unknown }).inputs).toEqual({
      sessionsRequired: 3, sessionsConsidered: 0, sessionsMetTarget: 0, maxPerceivedDifficulty: null,
      cooldown: { active: false, lastRaisedAt: null, until: null, days: 7 }, step: null,
    });

    // Three met sessions: 20 kg upper body -> +2.5% = 0.5 < 1.0 min step -> 21 kg.
    await session(u.id, plan, 6, { [a]: rep(2, { reps: 10, kg: 20 }), [b]: [{ sec: 60 }] }, { difficulty: 2 });
    await session(u.id, plan, 4, { [a]: rep(2, { reps: 10, kg: 20 }), [b]: [{ sec: 60 }] }, { difficulty: null });
    await session(u.id, plan, 2, { [a]: rep(2, { reps: 10, kg: 20 }), [b]: [{ sec: 60 }] }, { difficulty: 3 });
    s = await suggest(u.token, plan);
    check(s);
    const inp = (x: Sugg) => (x as unknown as { inputs: Record<string, unknown> }).inputs;
    expect(byEx(s, a).suggested!.targetWeightKg).toBe(21);
    expect(inp(byEx(s, a))).toMatchObject({
      sessionsConsidered: 3, sessionsMetTarget: 3, maxPerceivedDifficulty: 3, cooldown: { active: false, lastRaisedAt: null },
      step: { field: 'targetWeightKg', rule: 'percent_increase', percent: 2.5, capPercent: 10, capApplied: false, minStepApplied: true, rounding: 'down_0_5_kg', limitApplied: false },
    });
    expect(inp(byEx(s, b)).step).toMatchObject({ field: 'durationSeconds', rule: 'percent_increase', percent: 10, minStepApplied: false, rounding: 'down_5_s' });

    // Apply both; the raise is 3 days old -> cooldown active with lastRaisedAt.
    const pes = s.suggestions.map((x) => ({ ex: x.exerciseId, ...(x.suggested as object) }));
    await as(u.token).patch(`/api/v1/workout-plans/${plan}`).send({ ...draftOf(pes), baseVersion: s.planVersion }).expect(200);
    const v2 = await prisma.workoutPlanVersion.findUniqueOrThrow({ where: { planId_version: { planId: plan, version: 2 } } });
    const raisedAt = new Date(NOW.getTime() - 3 * DAY);
    await prisma.workoutPlanVersion.update({ where: { id: v2.id }, data: { createdAt: raisedAt } });
    // One v2 session that misses `a`: MISSED_TARGET decides the reason, but the cooldown is still reported.
    await session(u.id, plan, 1, { [a]: rep(1, { reps: 10, kg: 21 }), [b]: [{ sec: 65 }] }, { v: 2, difficulty: 4 });
    s = await suggest(u.token, plan);
    check(s);
    const until = new Date(raisedAt.getTime() + 7 * DAY).toISOString();
    expect(byEx(s, a)).toMatchObject({ reason: 'MISSED_TARGET', cooldownUntil: null });
    expect(inp(byEx(s, a))).toMatchObject({ sessionsConsidered: 3, sessionsMetTarget: 2, maxPerceivedDifficulty: 4, cooldown: { active: true, lastRaisedAt: raisedAt.toISOString(), until }, step: null });

    // 8 days later the cooldown has ended, but lastRaisedAt is still reported.
    await prisma.workoutPlanVersion.update({ where: { id: v2.id }, data: { createdAt: new Date(NOW.getTime() - 8 * DAY) } });
    s = await suggest(u.token, plan);
    check(s);
    expect(inp(byEx(s, a)).cooldown).toEqual({ active: false, lastRaisedAt: new Date(NOW.getTime() - 8 * DAY).toISOString(), until: null, days: 7 });
  });
});
