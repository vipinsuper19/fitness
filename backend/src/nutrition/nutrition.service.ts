import { HttpStatus, Injectable } from '@nestjs/common';

import { AchievementsService } from '../achievements/achievements.service';
import { EvaluatesAchievements } from '../achievements/evaluates-achievements.decorator';
import { ApiError } from '../common/api-error';

import { decodeCursor, encodeCursor } from '../common/cursor';
import { isoDate, num, parseIsoDate } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
import type { FoodItem, FoodLog, Prisma, WaterLog } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { MEAL_TYPES, MealTypeValue } from './nutrition.dto';
import type { FoodCreateDto, FoodLogCreateDto, FoodLogUpdateDto, FoodSearchQuery, FoodUpdateDto, NutrientsDto, WaterLogDto, WaterLogUpdateDto } from './nutrition.dto';

export interface Nutrients {
  energyKcal: number;
  proteinG: number;
  carbsG: number;
  fatG: number;
  fiberG: number | null;
  sugarG: number | null;
  sodiumMg: number | null;
}

export interface FoodView {
  id: string;
  name: string;
  nameHi: string | null;
  brand: string | null;
  servingSize: number;
  servingUnit: string;
  gramsPerServing: number | null;
  nutrients: Nutrients;
  isVegetarian: boolean | null;
  source: 'catalog' | 'custom';
}

export interface FoodLogView {
  id: string;
  foodId: string;
  foodName: string;
  date: string;
  mealType: string;
  servings: number;
  /** The serving the entry's nutrients were computed from (snapshot; falls back to the food). */
  servingSize: number;
  servingUnit: string;
  gramsPerServing: number | null;
  nutrients: Nutrients;
  nutrientsEdited: boolean;
  createdAt: string;
}

export interface WaterLogView {
  id: string;
  date: string;
  amountMl: number;
  createdAt: string;
}

/** How many of the day's entries have no value for each optional nutrient. */
export interface EntriesMissing {
  fiberG: number;
  sugarG: number;
  sodiumMg: number;
}

export interface DailyNutrition {
  date: string;
  /** Food log entries counted in the totals. */
  entryCount: number;
  /**
   * Entries without fibre, sugar or sodium. When > 0 and < entryCount, that
   * total covers only some entries (partial, an under-count); when equal to
   * entryCount the total is null.
   */
  entriesMissing: EntriesMissing;
  totals: Nutrients;
  byMeal: Record<MealTypeValue, Nutrients>;
  waterMl: number;
  calorieTargetKcal: number | null;
  waterTargetMl: number | null;
}

type NutrientRow = Pick<FoodItem, 'energyKcal' | 'proteinG' | 'carbsG' | 'fatG' | 'fiberG' | 'sugarG' | 'sodiumMg'>;

const r1 = (v: number) => Math.round(v * 10) / 10;

function nutrientsOf(row: NutrientRow): Nutrients {
  return {
    energyKcal: num(row.energyKcal)!,
    proteinG: num(row.proteinG)!,
    carbsG: num(row.carbsG)!,
    fatG: num(row.fatG)!,
    fiberG: num(row.fiberG),
    sugarG: num(row.sugarG),
    sodiumMg: num(row.sodiumMg),
  };
}

/** One serving's nutrients times servings, rounded to the column precision (0.1). */
export function scale(per: Nutrients, servings: number): Nutrients {
  const s = (v: number | null) => (v == null ? null : r1(v * servings));
  return {
    energyKcal: r1(per.energyKcal * servings),
    proteinG: r1(per.proteinG * servings),
    carbsG: r1(per.carbsG * servings),
    fatG: r1(per.fatG * servings),
    fiberG: s(per.fiberG),
    sugarG: s(per.sugarG),
    sodiumMg: s(per.sodiumMg),
  };
}

const fromDto = (n: NutrientsDto): Nutrients => ({
  energyKcal: n.energyKcal,
  proteinG: n.proteinG,
  carbsG: n.carbsG,
  fatG: n.fatG,
  fiberG: n.fiberG ?? null,
  sugarG: n.sugarG ?? null,
  sodiumMg: n.sodiumMg ?? null,
});

/** Sums entries; an optional nutrient stays null ("no data") unless some entry has it. */
export function sum(items: Nutrients[]): Nutrients {
  const add = (k: 'fiberG' | 'sugarG' | 'sodiumMg') => {
    const known = items.map((i) => i[k]).filter((v): v is number => v != null);
    return known.length ? r1(known.reduce((a, b) => a + b, 0)) : null;
  };
  return {
    energyKcal: r1(items.reduce((a, i) => a + i.energyKcal, 0)),
    proteinG: r1(items.reduce((a, i) => a + i.proteinG, 0)),
    carbsG: r1(items.reduce((a, i) => a + i.carbsG, 0)),
    fatG: r1(items.reduce((a, i) => a + i.fatG, 0)),
    fiberG: add('fiberG'),
    sugarG: add('sugarG'),
    sodiumMg: add('sodiumMg'),
  };
}

export const toFoodView = (f: FoodItem): FoodView => ({
  id: f.id,
  name: f.name,
  nameHi: f.nameHi,
  brand: f.brand,
  servingSize: num(f.servingSize)!,
  servingUnit: f.servingUnit,
  gramsPerServing: num(f.gramsPerServing),
  nutrients: nutrientsOf(f),
  isVegetarian: f.isVegetarian,
  source: f.source,
});

type LogFood = Pick<FoodItem, 'name' | 'servingSize' | 'servingUnit' | 'gramsPerServing'>;

export const toLogView = (l: FoodLog & { food: LogFood }): FoodLogView => {
  const snap = l.servingUnit != null && l.servingSize != null;
  return {
    id: l.id,
    foodId: l.foodId,
    foodName: l.food.name,
    date: isoDate(l.date)!,
    mealType: l.mealType,
    servings: num(l.servings)!,
    servingSize: num(snap ? l.servingSize : l.food.servingSize)!,
    servingUnit: snap ? l.servingUnit! : l.food.servingUnit,
    gramsPerServing: num(snap ? l.gramsPerServing : l.food.gramsPerServing),
    nutrients: nutrientsOf(l),
    nutrientsEdited: l.nutrientsEdited,
    createdAt: l.createdAt.toISOString(),
  };
};

export const toWaterView = (w: WaterLog): WaterLogView => ({
  id: w.id,
  date: isoDate(w.date)!,
  amountMl: w.amountMl,
  createdAt: w.createdAt.toISOString(),
});

export const withFood = { food: { select: { name: true, servingSize: true, servingUnit: true, gramsPerServing: true } } } as const;

const servingOf = (f: Pick<FoodItem, 'servingSize' | 'servingUnit' | 'gramsPerServing'>) => ({
  servingSize: f.servingSize,
  servingUnit: f.servingUnit,
  gramsPerServing: f.gramsPerServing,
});

const invalid = (fields: Record<string, string>) =>
  new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields });

/** The catalog plus the caller's own live custom foods; another user's or a deleted custom food is invisible. */
const visibleTo = (userId: string): Prisma.FoodItemWhereInput => ({ deletedAt: null, OR: [{ source: 'catalog' }, { ownerId: userId }] });

/** Only the caller's own live custom foods can be edited or deleted. */
const ownedBy = (userId: string): Prisma.FoodItemWhereInput => ({ deletedAt: null, source: 'custom', ownerId: userId });

@Injectable()
export class NutritionService {
  constructor(
    private readonly prisma: PrismaService,
    readonly achievements: AchievementsService,
  ) {}

  async searchFoods(userId: string, q: FoodSearchQuery): Promise<{ items: FoodView[]; nextCursor: string | null }> {
    const limit = q.limit ?? 20;
    const after = decodeCursor(q.cursor, ['name', 'id'] as const);
    const text = q.query;
    const rows = await this.prisma.foodItem.findMany({
      where: {
        AND: [
          visibleTo(userId),
          {
            OR: [
              { name: { contains: text, mode: 'insensitive' } },
              { nameHi: { contains: text, mode: 'insensitive' } },
              { brand: { contains: text, mode: 'insensitive' } },
              { slug: { contains: text.toLowerCase().replace(/\s+/g, '-') } },
            ],
          },
          ...(after ? [{ OR: [{ name: { gt: after.name } }, { name: after.name, id: { gt: after.id } }] }] : []),
        ],
      },
      orderBy: [{ name: 'asc' }, { id: 'asc' }],
      take: limit + 1,
    });
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    return {
      items: page.map(toFoodView),
      nextCursor: rows.length > limit && last ? encodeCursor({ name: last.name, id: last.id }) : null,
    };
  }

  async createFood(userId: string, dto: FoodCreateDto): Promise<FoodView> {
    const n = fromDto(dto.nutrients);
    const row = await this.prisma.foodItem.create({
      data: {
        ownerId: userId,
        source: 'custom',
        name: dto.name,
        brand: dto.brand ?? null,
        servingSize: dto.servingSize,
        servingUnit: dto.servingUnit,
        gramsPerServing: dto.gramsPerServing ?? null,
        isVegetarian: dto.isVegetarian ?? null,
        ...n,
      },
    });
    return toFoodView(row);
  }

  async getFood(userId: string, id: string): Promise<FoodView> {
    const row = await this.prisma.foodItem.findFirst({ where: { id, ...visibleTo(userId) } });
    if (!row) throw notFound('Food');
    return toFoodView(row);
  }

  /** Partial edit of the caller's own custom food. Existing diary entries keep their snapshotted nutrients and serving. */
  async updateFood(userId: string, id: string, dto: FoodUpdateDto): Promise<FoodView> {
    const keys = ['name', 'brand', 'servingSize', 'servingUnit', 'gramsPerServing', 'nutrients', 'isVegetarian'] as const;
    if (!keys.some((k) => dto[k] !== undefined)) throw invalid({ body: 'send at least one field to change' });
    const data: Prisma.FoodItemUpdateManyMutationInput = {};
    if (dto.name !== undefined) data.name = dto.name;
    if (dto.brand !== undefined) data.brand = dto.brand;
    if (dto.servingSize !== undefined) data.servingSize = dto.servingSize;
    if (dto.servingUnit !== undefined) data.servingUnit = dto.servingUnit;
    if (dto.gramsPerServing !== undefined) data.gramsPerServing = dto.gramsPerServing;
    if (dto.isVegetarian !== undefined) data.isVegetarian = dto.isVegetarian;
    if (dto.nutrients !== undefined) Object.assign(data, fromDto(dto.nutrients));
    return this.prisma.$transaction(async (tx) => {
      const food = await tx.foodItem.findFirst({ where: { id, ...ownedBy(userId) } });
      if (!food) throw notFound('Food');
      // Changing the unit away from g needs a weight per serving (create keeps it optional; see the contract).
      const unit = dto.servingUnit ?? food.servingUnit;
      const grams = dto.gramsPerServing !== undefined ? dto.gramsPerServing : food.gramsPerServing;
      if (dto.servingUnit !== undefined && dto.servingUnit !== food.servingUnit && unit !== 'g' && grams == null) {
        throw invalid({ gramsPerServing: 'required when servingUnit changes to a unit other than g' });
      }
      const { count } = await tx.foodItem.updateMany({ where: { id, ...ownedBy(userId) }, data });
      if (count !== 1) throw notFound('Food');
      return toFoodView(await tx.foodItem.findUniqueOrThrow({ where: { id } }));
    });
  }

  /**
   * Soft delete: the food disappears from search, GET and new logs, but existing
   * diary entries stay intact (they reference it and keep their snapshot).
   */
  async deleteFood(userId: string, id: string): Promise<void> {
    const { count } = await this.prisma.foodItem.updateMany({ where: { id, ...ownedBy(userId) }, data: { deletedAt: new Date() } });
    if (count !== 1) throw notFound('Food');
  }

  async listFoodLogs(userId: string, date: string): Promise<{ items: FoodLogView[] }> {
    const rows = await this.prisma.foodLog.findMany({
      where: { userId, date: parseIsoDate(date)! },
      orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
      include: withFood,
    });
    return { items: rows.map(toLogView) };
  }

  @EvaluatesAchievements()
  async createFoodLog(userId: string, dto: FoodLogCreateDto): Promise<FoodLogView> {
    const food = await this.prisma.foodItem.findFirst({ where: { id: dto.foodId, ...visibleTo(userId) } });
    if (!food) throw notFound('Food');
    const edited = dto.nutrientsOverride !== undefined;
    const nutrients = edited ? fromDto(dto.nutrientsOverride!) : scale(nutrientsOf(food), dto.servings);
    const row = await this.prisma.foodLog.create({
      data: { userId, foodId: food.id, date: parseIsoDate(dto.date)!, mealType: dto.mealType, servings: dto.servings, nutrientsEdited: edited, ...servingOf(food), ...nutrients },
      include: withFood,
    });
    return toLogView(row);
  }

  /**
   * Edits keep the user's own macros when they overrode them; otherwise the
   * entry is recomputed from the food for the new servings. A null override
   * restores the computed values.
   */
  @EvaluatesAchievements()
  async updateFoodLog(userId: string, id: string, dto: FoodLogUpdateDto): Promise<FoodLogView> {
    const log = await this.prisma.foodLog.findFirst({ where: { id, userId }, include: { food: true } });
    if (!log) throw notFound('Food log');
    const servings = dto.servings ?? num(log.servings)!;
    let nutrients: Nutrients | undefined;
    let serving: ReturnType<typeof servingOf> | undefined;
    let edited = log.nutrientsEdited;
    if (dto.nutrientsOverride) {
      nutrients = fromDto(dto.nutrientsOverride);
      edited = true;
    } else if (dto.nutrientsOverride === null || (!log.nutrientsEdited && dto.servings !== undefined)) {
      // Recomputed from the food as it is now, so the snapshot follows it.
      nutrients = scale(nutrientsOf(log.food), servings);
      serving = servingOf(log.food);
      edited = false;
    }
    const { count } = await this.prisma.foodLog.updateMany({
      where: { id, userId },
      data: { mealType: dto.mealType, servings, nutrientsEdited: edited, ...(serving ?? {}), ...(nutrients ?? {}) },
    });
    if (count !== 1) throw notFound('Food log');
    return toLogView(await this.prisma.foodLog.findUniqueOrThrow({ where: { id }, include: withFood }));
  }

  @EvaluatesAchievements()
  async deleteFoodLog(userId: string, id: string): Promise<void> {
    const { count } = await this.prisma.foodLog.deleteMany({ where: { id, userId } });
    if (count !== 1) throw notFound('Food log');
  }

  async listWater(userId: string, date: string): Promise<{ items: WaterLogView[] }> {
    const rows = await this.prisma.waterLog.findMany({
      where: { userId, date: parseIsoDate(date)! },
      orderBy: [{ createdAt: 'asc' }, { id: 'asc' }],
    });
    return { items: rows.map(toWaterView) };
  }

  @EvaluatesAchievements()
  async createWater(userId: string, dto: WaterLogDto): Promise<WaterLogView> {
    return toWaterView(await this.prisma.waterLog.create({ data: { userId, date: parseIsoDate(dto.date)!, amountMl: dto.amountMl } }));
  }

  /** Partial: a missing field stays unchanged; an empty body is 400. */
  @EvaluatesAchievements()
  async updateWater(userId: string, id: string, dto: WaterLogUpdateDto): Promise<WaterLogView> {
    if (dto.date === undefined && dto.amountMl === undefined) throw invalid({ body: 'send date and/or amountMl' });
    const { count } = await this.prisma.waterLog.updateMany({
      where: { id, userId },
      data: { ...(dto.date !== undefined ? { date: parseIsoDate(dto.date)! } : {}), ...(dto.amountMl !== undefined ? { amountMl: dto.amountMl } : {}) },
    });
    if (count !== 1) throw notFound('Water log');
    return toWaterView(await this.prisma.waterLog.findUniqueOrThrow({ where: { id } }));
  }

  @EvaluatesAchievements()
  async deleteWater(userId: string, id: string): Promise<void> {
    const { count } = await this.prisma.waterLog.deleteMany({ where: { id, userId } });
    if (count !== 1) throw notFound('Water log');
  }

  async daily(userId: string, date: string): Promise<DailyNutrition> {
    const day = parseIsoDate(date)!;
    const [logs, water, profile] = await Promise.all([
      this.prisma.foodLog.findMany({ where: { userId, date: day } }),
      this.prisma.waterLog.aggregate({ where: { userId, date: day }, _sum: { amountMl: true } }),
      this.prisma.userProfile.findUnique({ where: { userId }, select: { dailyCalorieTargetKcal: true, dailyWaterTargetMl: true } }),
    ]);
    const entries = logs.map((l) => ({ meal: l.mealType as MealTypeValue, n: nutrientsOf(l) }));
    const byMeal = Object.fromEntries(MEAL_TYPES.map((m) => [m, sum(entries.filter((e) => e.meal === m).map((e) => e.n))])) as Record<MealTypeValue, Nutrients>;
    const missing = (k: keyof EntriesMissing) => entries.filter((e) => e.n[k] == null).length;
    return {
      date,
      entryCount: entries.length,
      entriesMissing: { fiberG: missing('fiberG'), sugarG: missing('sugarG'), sodiumMg: missing('sodiumMg') },
      totals: sum(entries.map((e) => e.n)),
      byMeal,
      waterMl: water._sum.amountMl ?? 0,
      calorieTargetKcal: profile?.dailyCalorieTargetKcal ?? null,
      waterTargetMl: profile?.dailyWaterTargetMl ?? null,
    };
  }
}
