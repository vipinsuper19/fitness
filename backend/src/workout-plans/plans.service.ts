import { HttpStatus, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { num } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
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
}

export const versionInclude = {
  workouts: { orderBy: { order: 'asc' }, include: { exercises: { orderBy: { order: 'asc' } } } },
} satisfies Prisma.WorkoutPlanVersionInclude;

type VersionRow = Prisma.WorkoutPlanVersionGetPayload<{ include: typeof versionInclude }>;
type PlanRow = { id: string; source: 'rule_based' | 'custom'; createdAt: Date; activatedAt: Date | null };

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
  };
}

const invalid = (fields: Record<string, string>) =>
  new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields });

@Injectable()
export class PlansService {
  constructor(private readonly prisma: PrismaService) {}

  /** Never persists anything: a pure function of the request and the catalog. */
  async generate(dto: PlanGenerateDto): Promise<WorkoutPlanDraft> {
    const catalog = await this.prisma.exercise.findMany({
      select: { id: true, slug: true, name: true, primaryMuscle: true, secondaryMuscles: true, equipment: true, trackingType: true, difficulty: true },
      orderBy: { slug: 'asc' },
    });
    return generatePlan(dto, catalog as CatalogExercise[]);
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
    return toPlanView(plan, v, await this.activePlanId(userId));
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
    await this.check(dto);
    const next = dto.baseVersion + 1;
    await this.prisma.$transaction(async (tx) => {
      const { count } = await tx.workoutPlan.updateMany({
        where: { id: planId, userId, currentVersion: dto.baseVersion },
        data: { currentVersion: next, source: dto.generator },
      });
      if (count !== 1) {
        const current = await tx.workoutPlan.findFirst({ where: { id: planId, userId }, select: { currentVersion: true } });
        if (!current) throw notFound('Plan');
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
   * stamps activatedAt; re-activating the already active plan is a no-op and
   * keeps its timestamp.
   */
  async activate(userId: string, planId: string): Promise<PlanView> {
    await this.prisma.$transaction(async (tx) => {
      const plan = await tx.workoutPlan.findFirst({ where: { id: planId, userId }, select: { id: true } });
      if (!plan) throw notFound('Plan');
      const profile = await tx.userProfile.findUnique({ where: { userId }, select: { activePlanId: true } });
      if (profile?.activePlanId === planId) return;
      await tx.userProfile.upsert({
        where: { userId },
        create: { userId, activePlanId: planId },
        update: { activePlanId: planId },
      });
      await tx.workoutPlan.update({ where: { id: planId }, data: { activatedAt: new Date() } });
    });
    return this.get(userId, planId);
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
