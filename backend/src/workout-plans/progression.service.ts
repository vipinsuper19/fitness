import { HttpStatus, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { num } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
import { PrismaService } from '../prisma/prisma.service';
import {
  type Action, COOLDOWN_MS, deload, harderVariations, increase, type Level, metPrescription, type Prescription,
  raisedLoad, type Reason, REASON_TEXT, type SetRow, type TrackingType, WINDOW,
} from './progression.rules';

export interface ProgressionBasis {
  sessionId: string; completedAt: string; perceivedDifficulty: number | null; completedSets: number; plannedSets: number;
  metTarget: boolean; bestReps: number | null; bestWeightKg: number | null; bestDurationSeconds: number | null; bestDistanceM: number | null;
}
export interface ProgressionSuggestion {
  plannedWorkoutId: string; exerciseId: string; order: number; trackingType: TrackingType; action: Action; reason: Reason;
  reasonText: string; current: Prescription; suggested: Prescription | null; harderVariations?: string[];
  cooldownUntil: string | null; basedOn: ProgressionBasis[];
}
export interface ProgressionSuggestions {
  planId: string; planVersion: number; generatedAt: string; windowSessions: number; suggestions: ProgressionSuggestion[];
}

type PeRow = { exerciseId: string; sets: number; repsMin: number | null; repsMax: number | null; durationSeconds: number | null; targetWeightKg: { toNumber(): number } | null };
const presc = (pe: PeRow): Prescription => ({
  sets: pe.sets, repsMin: pe.repsMin, repsMax: pe.repsMax, targetWeightKg: num(pe.targetWeightKg), durationSeconds: pe.durationSeconds,
});
const maxOf = (xs: (number | null)[]) => xs.reduce<number | null>((m, x) => (x == null ? m : m == null || x > m ? x : m), null);

const workoutsInclude = { workouts: { orderBy: { order: 'asc' as const }, include: { exercises: { orderBy: { order: 'asc' as const } } } } };

/**
 * GET /workout-plans/{planId}/progression-suggestions (docs/specs/adaptive-progression.md).
 * Read-only and rule-based: never writes plans, sessions, XP or badges.
 */
@Injectable()
export class ProgressionService {
  constructor(private readonly prisma: PrismaService) {}

  async suggestions(userId: string, planId: string): Promise<ProgressionSuggestions> {
    const plan = await this.prisma.workoutPlan.findFirst({ where: { id: planId, userId } });
    if (!plan) throw notFound('Plan');
    if (plan.archivedAt) throw new ApiError(HttpStatus.CONFLICT, 'PLAN_ARCHIVED', 'This plan is archived. Save a new plan instead.');
    const now = new Date();

    const versions = await this.prisma.workoutPlanVersion.findMany({ where: { planId }, orderBy: { version: 'asc' }, include: workoutsInclude });
    const latest = versions.find((v) => v.version === plan.currentVersion) ?? versions[versions.length - 1];
    if (!latest) throw notFound('Plan version');
    const byVersionId = new Map(versions.map((v) => [v.id, v]));
    /** First prescription of `exerciseId` in a version (preferring the session's planned workout). */
    const prescIn = (versionId: string | null, exerciseId: string, plannedWorkoutId?: string | null): Prescription | undefined => {
      const v = versionId ? byVersionId.get(versionId) : undefined;
      if (!v) return undefined;
      const pw = v.workouts.find((w) => w.id === plannedWorkoutId)?.exercises.find((e) => e.exerciseId === exerciseId);
      const pe = pw ?? v.workouts.flatMap((w) => w.exercises).find((e) => e.exerciseId === exerciseId);
      return pe ? presc(pe) : undefined;
    };

    type Version = (typeof versions)[number];
    type Entry = { wi: number; ei: number };
    /**
     * The entry in `prev` that corresponds to `at` in `cur`. Workouts have no id that is stable across
     * versions (each version gets new rows), so the workout is matched by its unique (dayOfWeek, name),
     * falling back to the same order. Within it the exercise is matched by exerciseId; when an exercise
     * appears more than once in a workout, the k-th occurrence (by exercise order) matches the k-th.
     */
    const matchEntry = (cur: Version, prev: Version, at: Entry): Entry | undefined => {
      const cw = cur.workouts[at.wi]!;
      const exerciseId = cw.exercises[at.ei]!.exerciseId;
      const k = cw.exercises.slice(0, at.ei).filter((e) => e.exerciseId === exerciseId).length;
      const same = prev.workouts.flatMap((w, i) => (w.dayOfWeek === cw.dayOfWeek && w.name === cw.name ? [i] : []));
      const wi = same.length === 1 ? same[0]! : at.wi < prev.workouts.length ? at.wi : -1;
      if (wi < 0) return undefined;
      const idx = prev.workouts[wi]!.exercises.flatMap((e, i) => (e.exerciseId === exerciseId ? [i] : []));
      return idx[k] === undefined ? undefined : { wi, ei: idx[k]! };
    };
    const prescOf = (v: Version, e: Entry) => presc(v.workouts[e.wi]!.exercises[e.ei]!);

    const exerciseIds = [...new Set(latest.workouts.flatMap((w) => w.exercises.map((e) => e.exerciseId)))];
    // Only the newest N completed sessions per exercise (that logged a completed set of it).
    const historyFor = (exerciseId: string) =>
      this.prisma.workoutSession.findMany({
        where: { userId, status: 'completed', completedAt: { not: null }, planVersion: { planId }, sets: { some: { exerciseId, completed: true } } },
        orderBy: [{ completedAt: 'desc' }, { id: 'asc' }],
        take: WINDOW,
        select: {
          id: true, completedAt: true, perceivedDifficulty: true, planVersionId: true, plannedWorkoutId: true,
          sets: { where: { completed: true, exerciseId }, select: { exerciseId: true, reps: true, weightKg: true, durationSeconds: true, distanceM: true } },
        },
      });
    const [exercises, histories] = await Promise.all([
      this.prisma.exercise.findMany({ where: { id: { in: exerciseIds } } }),
      Promise.all(exerciseIds.map(async (id) => [id, await historyFor(id)] as const)),
    ]);
    const historyBy = new Map(histories);
    const exById = new Map(exercises.map((e) => [e.id, e]));
    const needsCatalog = exercises.some((e) => e.trackingType === 'reps');
    const catalog = needsCatalog
      ? await this.prisma.exercise.findMany({ where: { trackingType: 'reps' }, select: { id: true, name: true, primaryMuscle: true, trackingType: true, difficulty: true, equipment: true } })
      : [];

    const suggestions: ProgressionSuggestion[] = [];
    for (const [wi, w] of latest.workouts.entries()) {
      for (const [ei, pe] of w.exercises.entries()) {
        const ex = exById.get(pe.exerciseId)!;
        const t = ex.trackingType as TrackingType;
        const current = presc(pe);
        const window = (historyBy.get(pe.exerciseId) ?? []).map((s) => ({ s, sets: s.sets }));
        const basedOn: ProgressionBasis[] = window.map(({ s, sets }) => {
          const p = prescIn(s.planVersionId, pe.exerciseId, s.plannedWorkoutId) ?? current;
          const rows: SetRow[] = sets.map((x) => ({ reps: x.reps, weightKg: num(x.weightKg), durationSeconds: x.durationSeconds, distanceM: num(x.distanceM) }));
          return {
            sessionId: s.id, completedAt: s.completedAt!.toISOString(), perceivedDifficulty: s.perceivedDifficulty,
            completedSets: rows.length, plannedSets: p.sets, metTarget: metPrescription(t, p, rows),
            bestReps: maxOf(rows.map((r) => r.reps)), bestWeightKg: maxOf(rows.map((r) => r.weightKg)),
            bestDurationSeconds: maxOf(rows.map((r) => r.durationSeconds)), bestDistanceM: maxOf(rows.map((r) => r.distanceM)),
          };
        });

        // Cooldown: newest version that raised this entry's load over the matching entry of the previous
        // version, tracing the entry back through versions (survives workout and exercise reorders).
        let raisedAt: Date | null = null;
        let at: Entry | undefined = latest === versions[versions.length - 1] ? { wi, ei } : undefined;
        for (let i = versions.length - 1; i > 0 && at && !raisedAt; i--) {
          const prevAt = matchEntry(versions[i]!, versions[i - 1]!, at);
          if (prevAt && raisedLoad(prescOf(versions[i - 1]!, prevAt), prescOf(versions[i]!, at))) raisedAt = versions[i]!.createdAt;
          at = prevAt;
        }
        const cooldownUntil = raisedAt && now.getTime() - raisedAt.getTime() < COOLDOWN_MS ? new Date(raisedAt.getTime() + COOLDOWN_MS) : null;

        let action: Action = 'hold';
        let reason: Reason;
        let suggested: Prescription | null = null;
        let harder: string[] | undefined;
        const [a, b] = basedOn;
        const deloadReason: Reason | null =
          a && b && !a.metTarget && !b.metTarget ? 'REPEATED_MISSES'
            : a && b && a.perceivedDifficulty === 5 && b.perceivedDifficulty === 5 ? 'VERY_HARD' : null;
        if (deloadReason) {
          reason = deloadReason;
          suggested = deload(t, current);
          action = suggested ? 'deload' : 'hold';
        } else if (basedOn.length < WINDOW) reason = 'NOT_ENOUGH_HISTORY';
        else if (basedOn.some((x) => !x.metTarget)) reason = 'MISSED_TARGET';
        else if (basedOn.some((x) => (x.perceivedDifficulty ?? 0) >= 4)) reason = 'HIGH_EFFORT';
        else if (cooldownUntil) reason = 'COOLDOWN';
        else {
          const step = increase(t, current, ex.primaryMuscle, maxOf(basedOn.map((x) => x.bestWeightKg)));
          ({ action, reason, suggested } = step);
          if (step.harder) {
            harder = harderVariations(
              { id: ex.id, primaryMuscle: ex.primaryMuscle, trackingType: ex.trackingType, difficulty: ex.difficulty as Level, equipment: ex.equipment },
              catalog,
            );
          }
        }
        suggestions.push({
          plannedWorkoutId: w.id, exerciseId: pe.exerciseId, order: pe.order, trackingType: t, action, reason,
          reasonText: REASON_TEXT[reason], current, suggested, ...(harder ? { harderVariations: harder } : {}),
          cooldownUntil: reason === 'COOLDOWN' && cooldownUntil ? cooldownUntil.toISOString() : null, basedOn,
        });
      }
    }
    return { planId, planVersion: latest.version, generatedAt: now.toISOString(), windowSessions: WINDOW, suggestions };
  }
}
