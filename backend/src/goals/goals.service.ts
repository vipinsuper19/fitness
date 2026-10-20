import { HttpStatus, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { isoDate, num, parseIsoDate } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
import type { FitnessGoal } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import type { GoalCreateDto, GoalListQuery, GoalTypeValue, GoalUpdateDto } from './goals.dto';

export interface GoalView {
  id: string;
  type: string;
  status: string;
  targetValue: number | null;
  startValue: number | null;
  targetDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export const toGoalView = (g: FitnessGoal): GoalView => ({
  id: g.id,
  type: g.type,
  status: g.status,
  targetValue: num(g.targetValue),
  startValue: num(g.startValue),
  targetDate: isoDate(g.targetDate),
  createdAt: g.createdAt.toISOString(),
  updatedAt: g.updatedAt.toISOString(),
});

/** Ranges per goal type: weights are kg (same bounds as the profile), workouts are per ISO week. */
function checkValue(type: GoalTypeValue, field: 'targetValue' | 'startValue', v: number | null | undefined): void {
  if (v == null) return;
  const bad = (msg: string) =>
    new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields: { [field]: msg } });
  if (type === 'workouts_per_week') {
    if (!Number.isInteger(v) || v < 1 || v > 7) throw bad(`${field} must be a whole number of workouts from 1 to 7`);
  } else if (type === 'lose_weight' || type === 'gain_muscle' || type === 'maintain') {
    if (v < 20 || v > 400) throw bad(`${field} must be a body weight in kg from 20 to 400`);
  }
}

@Injectable()
export class GoalsService {
  constructor(private readonly prisma: PrismaService) {}

  async list(userId: string, q: GoalListQuery): Promise<{ items: GoalView[] }> {
    const rows = await this.prisma.fitnessGoal.findMany({
      where: { userId, ...(q.status ? { status: q.status } : {}) },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    });
    return { items: rows.map(toGoalView) };
  }

  async get(userId: string, id: string): Promise<GoalView> {
    return toGoalView(await this.owned(userId, id));
  }

  async create(userId: string, dto: GoalCreateDto): Promise<GoalView> {
    checkValue(dto.type, 'targetValue', dto.targetValue);
    checkValue(dto.type, 'startValue', dto.startValue);
    const row = await this.prisma.fitnessGoal.create({
      data: {
        userId,
        type: dto.type,
        targetValue: dto.targetValue ?? null,
        startValue: dto.startValue ?? null,
        targetDate: parseIsoDate(dto.targetDate) ?? null,
      },
    });
    return toGoalView(row);
  }

  async update(userId: string, id: string, dto: GoalUpdateDto): Promise<GoalView> {
    const goal = await this.owned(userId, id);
    checkValue(goal.type, 'targetValue', dto.targetValue);
    // Scoped by userId too, so a concurrent ownership change can never leak through.
    const { count } = await this.prisma.fitnessGoal.updateMany({
      where: { id, userId },
      data: { status: dto.status, targetValue: dto.targetValue, targetDate: parseIsoDate(dto.targetDate) },
    });
    if (count !== 1) throw notFound('Goal');
    return toGoalView(await this.owned(userId, id));
  }

  async remove(userId: string, id: string): Promise<void> {
    const { count } = await this.prisma.fitnessGoal.deleteMany({ where: { id, userId } });
    if (count !== 1) throw notFound('Goal');
  }

  private async owned(userId: string, id: string): Promise<FitnessGoal> {
    const goal = await this.prisma.fitnessGoal.findFirst({ where: { id, userId } });
    if (!goal) throw notFound('Goal');
    return goal;
  }
}
