import { HttpStatus, Injectable, Logger } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { decodeTimeCursor, encodeCursor } from '../common/cursor';
import { num } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
import { daySpan, localDate } from '../common/time';
import type { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { CatalogExercise, generatePlan, WorkoutPlanDraft } from './generator';
import type { PlanGenerateDto, WorkoutPlanDraftDto, WorkoutPlanUpdateDto } from './plans.dto';

export interface PlanView {
  id: string;
  version: number;
  name: string;
  weeks: number;
  rationale: string | null;
  workouts: {
    id: string;
    dayOfWeek: number;
    name: string;
    exercises: {
      exerciseId: string;
      order: number;
      sets: number;
      repsMin: number | null;
      repsMax: number | null;
      durationSeconds: number | null;
      targetWeightKg: number | null;
      restSeconds: number;
      notes: string | null;
    }[];
  }[];
  isActive: boolean;
  source: 'rule_based' | 'custom';
  createdAt: string;
  /** When the plan last became active; null if never activated. */
  activatedAt: string | null;
  /** 1-based week of the active plan, capped at `weeks`; null unless this is the active plan. */
  currentWeek: number | null;
  /** True once the active plan's last week is over; null unless this is the active plan. */
  planEnded: boolean | null;
  pausedAt: string | null;
  archivedAt: string | null;
}

export type PlanStatus = 'active' | 'paused' | 'inactive' | 'archived';
export const PLAN_STATUSES: readonly PlanStatus[] = ['active', 'paused', 'inactive', 'archived'];

/** WorkoutPlanSummary (contract): everything the plan list shows. */
export interface PlanSummary {
  id: string; name: string; source: 'rule_based' | 'custom'; version: number; status: PlanStatus; isActive: boolean;
  currentWeek: number | null; totalWeeks: number; activatedAt: string | null; pausedAt: string | null;
  archivedAt: string | null; createdAt: string; completedSessions: number;
}

/** Derived in contract order: archived, then active, then paused, else inactive. */
export function planStatus(p: { id: string; pausedAt?: Date | null; archivedAt?: Date | null }, activePlanId: string | null): PlanStatus {
  if (p.archivedAt) return 'archived';
  if (p.id === activePlanId) return 'active';
  return p.pausedAt ? 'paused' : 'inactive';
}

const planArchived = () =>
  new ApiError(HttpStatus.CONFLICT, 'PLAN_ARCHIVED', 'This plan is archived. Save a new plan instead.');

/**
 * Week of a plan activated at [activatedAt], counted in local calendar days of [tz]:
 * the activation day starts week 1, every 7 local days add a week. Capped at [weeks];
 * planEnded is true from the day after the last week.
 */
export function planWeek(activatedAt: Date, now: Date, tz: string, weeks: number): { currentWeek: number; planEnded: boolean } {
  const days = Math.max(0, daySpan(localDate(activatedAt, tz), localDate(now, tz)) - 1);
  const week = Math.floor(days / 7) + 1;
  return { currentWeek: Math.min(week, weeks), planEnded: week > weeks };
}

export const versionInclude = {
  workouts: { orderBy: { order: 'asc' }, include: { exercises: { orderBy: { order: 'asc' } } } },
} satisfies Prisma.WorkoutPlanVersionInclude;

type VersionRow = Prisma.WorkoutPlanVersionGetPayload<{ include: typeof versionInclude }>;
type PlanRow = { id: string; source: 'rule_based' | 'custom'; createdAt: Date; activatedAt: Date | null; pausedAt?: Date | null; archivedAt?: Date | null };

export function toPlanView(plan: PlanRow, v: VersionRow, activePlanId: string | null): PlanView {
  return {
    id: plan.id,
    version: v.version,
    name: v.name,
    weeks: v.weeks,
    rationale: v.rationale,
    workouts: v.workouts.map((w) => ({
      id: w.id,
      dayOfWeek: w.dayOfWeek,
      name: w.name,
      exercises: w.exercises.map((e) => ({
        exerciseId: e.exerciseId,
        order: e.order,
        sets: e.sets,
        repsMin: e.repsMin,
        repsMax: e.repsMax,
        durationSeconds: e.durationSeconds,
        targetWeightKg: num(e.targetWeightKg),
        restSeconds: e.restSeconds,
        notes: e.notes,
      })),
    })),
    isActive: activePlanId === plan.id,
    source: plan.source,
    createdAt: plan.createdAt.toISOString(),
    activatedAt: plan.activatedAt?.toISOString() ?? null,
    currentWeek: null,
    planEnded: null,
    pausedAt: plan.pausedAt?.toISOString() ?? null,
    archivedAt: plan.archivedAt?.toISOString() ?? null,
  };
}

const invalid = (fields: Record<string, string>) =>
  new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields });

@Injectable()
export class PlansService {
  private readonly logger = new Logger('PlansService');

  constructor(private readonly prisma: PrismaService) {}

  /** Never persists anything: a pure function of the request and the catalog. */
  async generate(dto: PlanGenerateDto): Promise<WorkoutPlanDraft> {
    const catalog = await this.prisma.exercise.findMany({
      select: { id: true, slug: true, name: true, primaryMuscle: true, secondaryMuscles: true, equipment: true, trackingType: true, difficulty: true },
      orderBy: { slug: 'asc' },
    });
    if (catalog.length === 0) {
      // Server-side problem (unseeded database), not something the user can fix.
      this.logger.error('generate: exercise catalog is empty; seed the database (npm run db:seed)');
      throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, 'EXERCISE_CATALOG_EMPTY', 'Workout plans are temporarily unavailable. Please try again later.');
    }
    const draft = generatePlan(dto, catalog as CatalogExercise[]);
    if (draft.workouts.length === 0) {
      // Catalog exists but nothing fits this level/equipment (bodyweight is always allowed).
      this.logger.warn(`generate: no exercises match level=${dto.experienceLevel} equipment=${(dto.equipment ?? []).join(',') || 'none'} (catalog size ${catalog.length})`);
      throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, 'NO_MATCHING_EXERCISES', 'No exercises match your experience level and equipment. Try adding equipment.', {
        experienceLevel: dto.experienceLevel,
        equipment: dto.equipment ?? [],
      });
    }
    return draft;
  }

  async create(userId: string, dto: WorkoutPlanDraftDto): Promise<PlanView> {
    await this.check(dto);
    const plan = await this.prisma.workoutPlan.create({
      data: { userId, source: dto.generator, currentVersion: 1, versions: { create: this.versionData(1, dto) } },
      include: { versions: { include: versionInclude } },
    });
    const active = await this.activePlanId(userId);
    return toPlanView(plan, plan.versions[0]!, active);
  }

  async get(userId: string, planId: string, version?: number): Promise<PlanView> {
    const plan = await this.prisma.workoutPlan.findFirst({ where: { id: planId, userId } });
    if (!plan) throw notFound('Plan');
    const v = await this.prisma.workoutPlanVersion.findUnique({
      where: { planId_version: { planId, version: version ?? plan.currentVersion } },
      include: versionInclude,
    });
    if (!v) throw notFound('Plan version');
    const profile = await this.prisma.userProfile.findUnique({ where: { userId }, select: { activePlanId: true, timezone: true } });
    const view = toPlanView(plan, v, profile?.activePlanId ?? null);
    if (view.isActive && plan.activatedAt) {
      // Same timezone rule as the progress summary: the profile's, defaulting to Asia/Kolkata.
      Object.assign(view, planWeek(plan.activatedAt, new Date(), profile?.timezone ?? 'Asia/Kolkata', v.weeks));
    }
    return view;
  }

  async active(userId: string): Promise<PlanView | null> {
    const id = await this.activePlanId(userId);
    return id ? this.get(userId, id) : null;
  }

  /**
   * PATCH creates version baseVersion + 1. The compare-and-set on
   * currentVersion makes two concurrent edits from the same base produce
   * one new version and one 409 VERSION_CONFLICT.
   */
  async update(userId: string, planId: string, dto: WorkoutPlanUpdateDto): Promise<PlanView> {
    const plan = await this.prisma.workoutPlan.findFirst({ where: { id: planId, userId } });
    if (!plan) throw notFound('Plan');
    if (plan.archivedAt) throw planArchived();
    await this.check(dto);
    const next = dto.baseVersion + 1;
    await this.prisma.$transaction(async (tx) => {
      const { count } = await tx.workoutPlan.updateMany({
        where: { id: planId, userId, currentVersion: dto.baseVersion, archivedAt: null },
        data: { currentVersion: next, source: dto.generator },
      });
      if (count !== 1) {
        const current = await tx.workoutPlan.findFirst({ where: { id: planId, userId }, select: { currentVersion: true, archivedAt: true } });
        if (!current) throw notFound('Plan');
        if (current.archivedAt) throw planArchived();
        throw new ApiError(HttpStatus.CONFLICT, 'VERSION_CONFLICT', 'This plan changed since you opened it. Reload and edit the latest version.', {
          currentVersion: current.currentVersion,
        });
      }
      await tx.workoutPlanVersion.create({ data: { planId, ...this.versionData(next, dto) } });
    });
    return this.get(userId, planId, next);
  }

  /**
   * One active plan per user: user_profiles.active_plan_id holds it. Activating
   * stamps activatedAt = now (week 1) and clears pausedAt; with `resume` on a paused
   * plan, activatedAt moves forward by the whole pause instead, so the week count
   * continues. Re-activating the already active plan is a no-op and keeps its
   * timestamp. The previously active plan becomes inactive (not paused).
   * Archived plans are read-only: 409 PLAN_ARCHIVED.
   */
  async activate(userId: string, planId: string, resume = false): Promise<PlanView> {
    await this.prisma.$transaction(async (tx) => {
      const plan = await tx.workoutPlan.findFirst({ where: { id: planId, userId }, select: { id: true, activatedAt: true, pausedAt: true, archivedAt: true } });
      if (!plan) throw notFound('Plan');
      if (plan.archivedAt) throw planArchived();
      const profile = await tx.userProfile.findUnique({ where: { userId }, select: { activePlanId: true } });
      if (profile?.activePlanId === planId) return;
      const now = new Date();
      const activatedAt =
        resume && plan.pausedAt && plan.activatedAt ? new Date(plan.activatedAt.getTime() + Math.max(0, now.getTime() - plan.pausedAt.getTime())) : now;
      // Guarded on archived_at so a concurrent archive can't leave an archived plan active.
      const { count } = await tx.workoutPlan.updateMany({ where: { id: planId, archivedAt: null }, data: { activatedAt, pausedAt: null } });
      if (count !== 1) throw planArchived();
      await tx.userProfile.upsert({
        where: { userId },
        create: { userId, activePlanId: planId },
        update: { activePlanId: planId },
      });
    });
    return this.get(userId, planId);
  }

  /**
   * Pause: if this is the active plan, clear the user's active plan and stamp pausedAt.
   * Anything else (inactive, paused, archived) is a no-op. The conditional update on
   * active_plan_id makes concurrent pauses/activations converge.
   */
  async deactivate(userId: string, planId: string): Promise<void> {
    await this.prisma.$transaction(async (tx) => {
      await this.pauseIfActive(tx, userId, planId, new Date());
    });
  }

  /**
   * Archive (soft delete): pauses the plan first if active, then sets archivedAt once.
   * Repeating is a no-op that keeps the first archivedAt. Nothing is deleted.
   */
  async archive(userId: string, planId: string): Promise<void> {
    await this.prisma.$transaction(async (tx) => {
      const now = new Date();
      await this.pauseIfActive(tx, userId, planId, now);
      await tx.workoutPlan.updateMany({ where: { id: planId, userId, archivedAt: null }, data: { archivedAt: now } });
    });
  }

  private async pauseIfActive(tx: Prisma.TransactionClient, userId: string, planId: string, now: Date): Promise<void> {
    const plan = await tx.workoutPlan.findFirst({ where: { id: planId, userId }, select: { id: true } });
    if (!plan) throw notFound('Plan');
    const { count } = await tx.userProfile.updateMany({ where: { userId, activePlanId: planId }, data: { activePlanId: null } });
    if (count === 1) await tx.workoutPlan.update({ where: { id: planId }, data: { pausedAt: now } });
  }

  /** GET /workout-plans: newest createdAt first (ties by id); default hides archived. */
  async list(userId: string, q: { status?: PlanStatus; cursor?: string; limit?: number }): Promise<{ items: PlanSummary[]; nextCursor: string | null }> {
    const limit = q.limit ?? 20;
    const after = decodeTimeCursor(q.cursor);
    const profile = await this.prisma.userProfile.findUnique({ where: { userId }, select: { activePlanId: true, timezone: true } });
    const activeId = profile?.activePlanId ?? null;
    const notActive: Prisma.WorkoutPlanWhereInput = activeId ? { id: { not: activeId } } : {};
    const byStatus: Record<PlanStatus, Prisma.WorkoutPlanWhereInput> = {
      archived: { archivedAt: { not: null } },
      active: activeId ? { id: activeId, archivedAt: null } : { id: { in: [] } },
      paused: { archivedAt: null, pausedAt: { not: null }, ...notActive },
      inactive: { archivedAt: null, pausedAt: null, ...notActive },
    };
    const rows = await this.prisma.workoutPlan.findMany({
      where: {
        userId,
        ...(q.status ? byStatus[q.status] : { archivedAt: null }),
        ...(after ? { OR: [{ createdAt: { lt: after.t } }, { createdAt: after.t, id: { lt: after.id } }] } : {}),
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: limit + 1,
    });
    const page = rows.slice(0, limit);
    const ids = page.map((p) => p.id);
    const versions = ids.length
      ? await this.prisma.workoutPlanVersion.findMany({ where: { planId: { in: ids } }, select: { id: true, planId: true, version: true, name: true, weeks: true } })
      : [];
    const done = ids.length
      ? await this.prisma.workoutSession.groupBy({ by: ['planVersionId'], where: { userId, status: 'completed', planVersionId: { in: versions.map((v) => v.id) } }, _count: { _all: true } })
      : [];
    const planOfVersion = new Map(versions.map((v) => [v.id, v.planId]));
    const sessions = new Map<string, number>();
    for (const d of done) {
      const pid = d.planVersionId ? planOfVersion.get(d.planVersionId) : undefined;
      if (pid) sessions.set(pid, (sessions.get(pid) ?? 0) + d._count._all);
    }
    const now = new Date();
    const tz = profile?.timezone ?? 'Asia/Kolkata';
    const items = page.map((p): PlanSummary => {
      const latest = versions.find((v) => v.planId === p.id && v.version === p.currentVersion)!;
      const status = planStatus(p, activeId);
      return {
        id: p.id, name: latest.name, source: p.source, version: p.currentVersion, status, isActive: status === 'active',
        currentWeek: status === 'active' && p.activatedAt ? planWeek(p.activatedAt, now, tz, latest.weeks).currentWeek : null,
        totalWeeks: latest.weeks,
        activatedAt: p.activatedAt?.toISOString() ?? null, pausedAt: p.pausedAt?.toISOString() ?? null,
        archivedAt: p.archivedAt?.toISOString() ?? null, createdAt: p.createdAt.toISOString(),
        completedSessions: sessions.get(p.id) ?? 0,
      };
    });
    const last = page[page.length - 1];
    return { items, nextCursor: rows.length > limit && last ? encodeCursor({ t: last.createdAt.toISOString(), id: last.id }) : null };
  }

  private async activePlanId(userId: string): Promise<string | null> {
    const p = await this.prisma.userProfile.findUnique({ where: { userId }, select: { activePlanId: true } });
    return p?.activePlanId ?? null;
  }

  /** Rules the JSON schema cannot express: real catalog ids, rep ranges, unique order per workout. */
  private async check(dto: WorkoutPlanDraftDto): Promise<void> {
    const fields: Record<string, string> = {};
    const ids = new Set<string>();
    dto.workouts.forEach((w, wi) => {
      const orders = new Set<number>();
      w.exercises.forEach((e, ei) => {
        const at = `workouts.${wi}.exercises.${ei}`;
        ids.add(e.exerciseId.toLowerCase());
        if (orders.has(e.order)) fields[`${at}.order`] = 'order must be unique within a workout';
        orders.add(e.order);
        if (e.repsMin != null && e.repsMax != null && e.repsMin > e.repsMax) fields[`${at}.repsMax`] = 'repsMax must be at least repsMin';
      });
    });
    const found = await this.prisma.exercise.findMany({ where: { id: { in: [...ids] } }, select: { id: true } });
    const known = new Set(found.map((f) => f.id));
    dto.workouts.forEach((w, wi) =>
      w.exercises.forEach((e, ei) => {
        if (!known.has(e.exerciseId.toLowerCase())) fields[`workouts.${wi}.exercises.${ei}.exerciseId`] = 'exerciseId is not in the exercise catalog';
      }),
    );
    if (Object.keys(fields).length) throw invalid(fields);
  }

  private versionData(version: number, dto: WorkoutPlanDraftDto) {
    return {
      version,
      name: dto.name,
      weeks: dto.weeks,
      rationale: dto.rationale ?? null,
      workouts: {
        create: dto.workouts.map((w, order) => ({
          dayOfWeek: w.dayOfWeek,
          name: w.name,
          order,
          exercises: {
            create: w.exercises.map((e) => ({
              exerciseId: e.exerciseId.toLowerCase(),
              order: e.order,
              sets: e.sets,
              repsMin: e.repsMin ?? null,
              repsMax: e.repsMax ?? null,
              durationSeconds: e.durationSeconds ?? null,
              targetWeightKg: e.targetWeightKg ?? null,
              restSeconds: e.restSeconds ?? 90,
              notes: e.notes ?? null,
            })),
          },
        })),
      },
    };
  }
}
