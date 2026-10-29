/**
 * GET /workout-plans/{planId}/progression-suggestions (docs/specs/adaptive-progression.md)
 * over HTTP against Postgres (DB_TESTS=1). Clock faked (Date only) at 2026-10-29 12:00 IST;
 * sessions are inserted with fixed completedAt values before that.
 */
import { randomUUID } from 'node:crypto';

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
});
