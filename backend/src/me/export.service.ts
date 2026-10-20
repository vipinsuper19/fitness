import { Injectable } from '@nestjs/common';

import { toPublicUser } from '../auth/auth.service';
import { toGoalView } from '../goals/goals.service';
import { notFound } from '../common/id-param.pipe';
import { toFoodView, toLogView, toWaterView, withFood } from '../nutrition/nutrition.service';
import { PrismaService } from '../prisma/prisma.service';
import { toProfileView } from '../profile/profile.service';
import { toMeasurementView } from '../progress/progress.service';
import { toPlanView, versionInclude } from '../workout-plans/plans.service';
import { sessionInclude, toSessionView } from '../workout-sessions/sessions.service';
import { toXpView } from '../xp/xp.service';

/** Per-collection row cap; collections that hit it are listed in `truncated` (newest rows are kept). */
export const EXPORT_MAX_ROWS = 5000;
/** Plan versions and set logs are nested, so they get their own caps. */
export const EXPORT_MAX_PLAN_VERSIONS = 1000;
export const EXPORT_MAX_SETS_PER_SESSION = 500;

/**
 * Builds the signed-in user's export. Every query is scoped by userId (custom foods by ownerId) and uses
 * explicit views, so password hashes, refresh tokens and idempotency rows can never appear.
 */
@Injectable()
export class ExportService {
  constructor(private readonly prisma: PrismaService) {}

  async export(userId: string, now = new Date()) {
    const take = EXPORT_MAX_ROWS + 1;
    const user = await this.prisma.user.findUnique({ where: { id: userId }, select: { id: true, email: true, displayName: true, createdAt: true } });
    if (!user) throw notFound('User');
    const [profile, goals, versions, sessions, customFoods, foodLogs, waterLogs, measurements, xp] = await Promise.all([
      this.prisma.userProfile.findUnique({ where: { userId } }),
      this.prisma.fitnessGoal.findMany({ where: { userId }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take }),
      this.prisma.workoutPlanVersion.findMany({
        where: { plan: { userId } },
        orderBy: [{ plan: { createdAt: 'desc' } }, { planId: 'desc' }, { version: 'desc' }],
        take: EXPORT_MAX_PLAN_VERSIONS + 1,
        include: { ...versionInclude, plan: { select: { id: true, source: true, createdAt: true } } },
      }),
      this.prisma.workoutSession.findMany({
        where: { userId },
        orderBy: [{ startedAt: 'desc' }, { id: 'desc' }],
        take,
        include: { ...sessionInclude, sets: { ...sessionInclude.sets, take: EXPORT_MAX_SETS_PER_SESSION + 1 } },
      }),
      this.prisma.foodItem.findMany({ where: { ownerId: userId }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take }),
      this.prisma.foodLog.findMany({ where: { userId }, orderBy: [{ date: 'desc' }, { id: 'desc' }], take, include: withFood }),
      this.prisma.waterLog.findMany({ where: { userId }, orderBy: [{ date: 'desc' }, { id: 'desc' }], take }),
      this.prisma.bodyMeasurement.findMany({ where: { userId }, orderBy: [{ date: 'desc' }, { id: 'desc' }], take }),
      this.prisma.xPTransaction.findMany({ where: { userId }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take }),
    ]);

    const truncated: string[] = [];
    const cap = <T>(name: string, rows: T[], max = EXPORT_MAX_ROWS): T[] => {
      if (rows.length > max) truncated.push(name);
      return rows.slice(0, max);
    };
    let setsCut = false;
    const sessionRows = cap('workoutSessions', sessions).map((s) => {
      if (s.sets.length > EXPORT_MAX_SETS_PER_SESSION) setsCut = true;
      return { ...s, sets: s.sets.slice(0, EXPORT_MAX_SETS_PER_SESSION) };
    });
    if (setsCut) truncated.push('workoutSessions.sets');
    const activePlanId = profile?.activePlanId ?? null;

    return {
      exportedAt: now.toISOString(),
      user: toPublicUser(user),
      ...(profile ? { profile: toProfileView(profile, user.displayName) } : {}),
      goals: cap('goals', goals).map(toGoalView),
      // One entry per plan version (id + version), newest plan first, newest version first.
      workoutPlans: cap('workoutPlans', versions, EXPORT_MAX_PLAN_VERSIONS).map((v) => toPlanView(v.plan, v, activePlanId)),
      workoutSessions: sessionRows.map((s) => toSessionView(s, now)),
      customFoods: cap('customFoods', customFoods).map(toFoodView),
      foodLogs: cap('foodLogs', foodLogs).map(toLogView),
      waterLogs: cap('waterLogs', waterLogs).map(toWaterView),
      bodyMeasurements: cap('bodyMeasurements', measurements).map(toMeasurementView),
      xpLedger: cap('xpLedger', xp).map(toXpView),
      truncated,
    };
  }
}
