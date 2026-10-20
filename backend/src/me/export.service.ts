import { Inject, Injectable } from '@nestjs/common';

import { toPublicUser } from '../auth/auth.service';
import { notFound } from '../common/id-param.pipe';
import { ENV } from '../config/config.module';
import type { Env } from '../config/env';
import { toGoalView } from '../goals/goals.service';
import { toFoodView, toLogView, toWaterView, withFood } from '../nutrition/nutrition.service';
import { PrismaService } from '../prisma/prisma.service';
import { toProfileView } from '../profile/profile.service';
import { toMeasurementView } from '../progress/progress.service';
import { toPlanView } from '../workout-plans/plans.service';
import { toSessionView } from '../workout-sessions/sessions.service';
import { toXpView } from '../xp/xp.service';

/**
 * Builds the signed-in user's export.
 * - Every query is scoped by userId (custom foods by ownerId) and mapped through explicit views, so password
 *   hashes, refresh tokens and idempotency rows can never appear.
 * - Memory is bounded: each query takes at most cap+1 rows (newest first), nested set logs and planned
 *   exercises have their own *total* caps, and queries run one after another. Cut collections are named
 *   in `truncated`.
 */
@Injectable()
export class ExportService {
  constructor(
    private readonly prisma: PrismaService,
    @Inject(ENV) private readonly env: Env,
  ) {}

  async export(userId: string, now = new Date()) {
    const env = this.env;
    const db = this.prisma;
    const truncated: string[] = [];
    const cap = <T>(name: string, rows: T[], max: number): T[] => {
      if (rows.length > max) truncated.push(name);
      return rows.slice(0, max);
    };
    const rows = env.EXPORT_MAX_ROWS;

    const user = await db.user.findUnique({ where: { id: userId }, select: { id: true, email: true, displayName: true, createdAt: true } });
    if (!user) throw notFound('User');
    const profile = await db.userProfile.findUnique({ where: { userId } });
    const goals = cap('goals', await db.fitnessGoal.findMany({ where: { userId }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take: rows + 1 }), rows);

    // Plans: versions (with their few workouts) first, then one bounded query for all their exercises.
    const versions = cap(
      'workoutPlans',
      await db.workoutPlanVersion.findMany({
        where: { plan: { userId } },
        orderBy: [{ createdAt: 'desc' }, { planId: 'desc' }, { version: 'desc' }],
        take: env.EXPORT_MAX_PLAN_VERSIONS + 1,
        include: { workouts: { orderBy: { order: 'asc' } }, plan: { select: { id: true, source: true, createdAt: true } } },
      }),
      env.EXPORT_MAX_PLAN_VERSIONS,
    );
    const workoutIds = versions.flatMap((v) => v.workouts.map((w) => w.id));
    const exercises = workoutIds.length
      ? cap(
          'workoutPlans.exercises',
          await db.plannedExercise.findMany({
            where: { plannedWorkoutId: { in: workoutIds } },
            orderBy: [{ plannedWorkout: { planVersion: { createdAt: 'desc' } } }, { plannedWorkoutId: 'desc' }, { order: 'asc' }, { id: 'asc' }],
            take: env.EXPORT_MAX_PLAN_EXERCISES_TOTAL + 1,
          }),
          env.EXPORT_MAX_PLAN_EXERCISES_TOTAL,
        )
      : [];
    const exByWorkout = group(exercises, (e) => e.plannedWorkoutId);
    const activePlanId = profile?.activePlanId ?? null;
    const workoutPlans = versions.map((v) =>
      toPlanView(v.plan, { ...v, workouts: v.workouts.map((w) => ({ ...w, exercises: exByWorkout.get(w.id) ?? [] })) }, activePlanId),
    );

    // Sessions, then one bounded newest-first query for the sets of exactly those sessions.
    const sessions = cap(
      'workoutSessions',
      await db.workoutSession.findMany({
        where: { userId },
        orderBy: [{ startedAt: 'desc' }, { id: 'desc' }],
        take: env.EXPORT_MAX_SESSIONS + 1,
        include: { planVersion: { select: { planId: true, version: true } } },
      }),
      env.EXPORT_MAX_SESSIONS,
    );
    const sets = sessions.length
      ? cap(
          'workoutSessions.sets',
          await db.exerciseSetLog.findMany({
            where: { userId, sessionId: { in: sessions.map((s) => s.id) } },
            orderBy: [{ loggedAt: 'desc' }, { id: 'desc' }],
            take: env.EXPORT_MAX_SETS_TOTAL + 1,
          }),
          env.EXPORT_MAX_SETS_TOTAL,
        )
      : [];
    const setsBySession = group(sets, (s) => s.sessionId);
    const workoutSessions = sessions.map((s) => {
      const own = (setsBySession.get(s.id) ?? []).sort(
        (a, b) => a.setIndex - b.setIndex || a.loggedAt.getTime() - b.loggedAt.getTime() || a.id.localeCompare(b.id),
      );
      return toSessionView({ ...s, sets: own }, now);
    });

    const customFoods = cap('customFoods', await db.foodItem.findMany({ where: { ownerId: userId }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take: rows + 1 }), rows);
    const foodLogs = cap('foodLogs', await db.foodLog.findMany({ where: { userId }, orderBy: [{ date: 'desc' }, { id: 'desc' }], take: rows + 1, include: withFood }), rows);
    const waterLogs = cap('waterLogs', await db.waterLog.findMany({ where: { userId }, orderBy: [{ date: 'desc' }, { id: 'desc' }], take: rows + 1 }), rows);
    const measurements = cap('bodyMeasurements', await db.bodyMeasurement.findMany({ where: { userId }, orderBy: [{ date: 'desc' }, { id: 'desc' }], take: rows + 1 }), rows);
    const xp = cap('xpLedger', await db.xPTransaction.findMany({ where: { userId }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take: rows + 1 }), rows);

    return {
      exportedAt: now.toISOString(),
      user: toPublicUser(user),
      ...(profile ? { profile: toProfileView(profile, user.displayName) } : {}),
      goals: goals.map(toGoalView),
      // One entry per plan version (id + version), newest first.
      workoutPlans,
      workoutSessions,
      customFoods: customFoods.map(toFoodView),
      foodLogs: foodLogs.map(toLogView),
      waterLogs: waterLogs.map(toWaterView),
      bodyMeasurements: measurements.map(toMeasurementView),
      xpLedger: xp.map(toXpView),
      truncated,
    };
  }
}

function group<T>(items: T[], key: (t: T) => string): Map<string, T[]> {
  const out = new Map<string, T[]>();
  for (const it of items) {
    const k = key(it);
    const list = out.get(k);
    if (list) list.push(it);
    else out.set(k, [it]);
  }
  return out;
}
