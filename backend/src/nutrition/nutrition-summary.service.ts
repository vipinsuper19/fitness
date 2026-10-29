import { HttpStatus, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { isoDate, parseIsoDate } from '../common/decimal';
import { dateRange, daySpan } from '../common/time';
import { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import type { NutritionSummaryQuery, SummaryBucket } from './nutrition.dto';

/**
 * GET /nutrition/summary (docs/specs/nutrition-summary.md). Food and water logs carry
 * a local date (profile timezone) already, so days are grouped in SQL by `date` and
 * folded into Monday weeks or calendar months here, clipped to from/to. Sums use
 * exact decimals; averages and percentages are rounded once, HALF_UP.
 */
export const MAX_NUTRITION_SUMMARY_DAYS = 366;
const DEFAULT_TZ = 'Asia/Kolkata';
const D = Prisma.Decimal;
type Dec = Prisma.Decimal;
const HALF_UP = Prisma.Decimal.ROUND_HALF_UP;

export interface SummaryNutrients {
  energyKcal: number; proteinG: number; carbsG: number; fatG: number;
  fiberG: number | null; sugarG: number | null; sodiumMg: number | null;
}
export interface NutritionTargets { kcal: number | null; proteinG: number | null; carbsG: number | null; fatG: number | null; waterMl: number | null }
export interface NutritionAdherence { kcalPct: number | null; proteinPct: number | null; carbsPct: number | null; fatPct: number | null; waterPct: number | null }
export interface NutritionSummaryPoint {
  period: string;
  daysInPeriod: number;
  daysLogged: number;
  totals: SummaryNutrients | null;
  avgPerLoggedDay: SummaryNutrients | null;
  waterMl: number | null;
  waterDaysLogged: number;
  avgWaterMlPerLoggedDay: number | null;
  adherence: NutritionAdherence | null;
  entryCount: number;
  entriesMissing: { fiberG: number; sugarG: number; sodiumMg: number };
}
export interface NutritionSummary {
  from: string; to: string; bucket: SummaryBucket; timezone: string;
  targets: NutritionTargets | null;
  points: NutritionSummaryPoint[];
}

const MACROS = ['energyKcal', 'proteinG', 'carbsG', 'fatG'] as const;
const OPTIONAL = ['fiberG', 'sugarG', 'sodiumMg'] as const;

interface Acc {
  period: string;
  days: number;
  foodDays: number;
  entries: number;
  sums: Record<(typeof MACROS)[number] | (typeof OPTIONAL)[number], Dec>;
  known: Record<(typeof OPTIONAL)[number], number>;
  water: number;
  waterDays: number;
}

const invalid = (fields: Record<string, string>) =>
  new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields });

/** First date of `date`'s period (Monday week or calendar month), clipped to `from`. */
export function periodStart(date: string, bucket: SummaryBucket, from: string): string {
  let start = date;
  if (bucket === 'week') {
    const d = new Date(`${date}T00:00:00Z`);
    const back = (d.getUTCDay() + 6) % 7; // Monday = 0
    start = new Date(d.getTime() - back * 86_400_000).toISOString().slice(0, 10);
  } else if (bucket === 'month') {
    start = `${date.slice(0, 7)}-01`;
  }
  return start < from ? from : start;
}

/** A target of 0 (or none) counts as no target. */
const target = (v: number | null | undefined): number | null => (v == null || v <= 0 ? null : v);
const r1 = (v: Dec) => v.toDecimalPlaces(1, HALF_UP).toNumber();
const pct = (avg: Dec | null, t: number | null) => (avg == null || t == null ? null : r1(avg.div(t).times(100)));

@Injectable()
export class NutritionSummaryService {
  constructor(private readonly prisma: PrismaService) {}

  async summary(userId: string, q: NutritionSummaryQuery): Promise<NutritionSummary> {
    const bucket: SummaryBucket = q.bucket ?? 'day';
    if (q.from > q.to) throw invalid({ from: 'from must be on or before to' });
    if (daySpan(q.from, q.to) > MAX_NUTRITION_SUMMARY_DAYS) throw invalid({ to: `the range can cover at most ${MAX_NUTRITION_SUMMARY_DAYS} days` });
    const range = { gte: parseIsoDate(q.from)!, lte: parseIsoDate(q.to)! };

    // Only this user's rows. Logs keep their nutrient snapshot, so a food deleted later
    // still counts; a deleted log row is gone.
    const [food, water, profile] = await Promise.all([
      this.prisma.foodLog.groupBy({
        by: ['date'],
        where: { userId, date: range },
        _sum: { energyKcal: true, proteinG: true, carbsG: true, fatG: true, fiberG: true, sugarG: true, sodiumMg: true },
        _count: { _all: true, fiberG: true, sugarG: true, sodiumMg: true },
      }),
      this.prisma.waterLog.groupBy({ by: ['date'], where: { userId, date: range }, _sum: { amountMl: true } }),
      this.prisma.userProfile.findUnique({ where: { userId }, select: { timezone: true, dailyCalorieTargetKcal: true, dailyWaterTargetMl: true, dailyProteinTargetG: true, dailyCarbsTargetG: true, dailyFatTargetG: true } }),
    ]);

    const kcal = target(profile?.dailyCalorieTargetKcal);
    const waterTarget = target(profile?.dailyWaterTargetMl);
    // Current targets apply to every period (no target history). Each macro is independent.
    const proteinT = target(profile?.dailyProteinTargetG);
    const carbsT = target(profile?.dailyCarbsTargetG);
    const fatT = target(profile?.dailyFatTargetG);
    const targets: NutritionTargets | null =
      [kcal, waterTarget, proteinT, carbsT, fatT].every((t) => t == null)
        ? null
        : { kcal, proteinG: proteinT, carbsG: carbsT, fatG: fatT, waterMl: waterTarget };

    const accs = new Map<string, Acc>();
    const byDay = new Map<string, Acc>();
    for (const day of dateRange(q.from, q.to, MAX_NUTRITION_SUMMARY_DAYS)) {
      const key = periodStart(day, bucket, q.from);
      let a = accs.get(key);
      if (!a) {
        const zero = () => new D(0);
        a = {
          period: key, days: 0, foodDays: 0, entries: 0, water: 0, waterDays: 0,
          sums: { energyKcal: zero(), proteinG: zero(), carbsG: zero(), fatG: zero(), fiberG: zero(), sugarG: zero(), sodiumMg: zero() },
          known: { fiberG: 0, sugarG: 0, sodiumMg: 0 },
        };
        accs.set(key, a);
      }
      a.days += 1;
      byDay.set(day, a);
    }
    for (const f of food) {
      const a = byDay.get(isoDate(f.date)!);
      if (!a || f._count._all === 0) continue;
      a.foodDays += 1;
      a.entries += f._count._all;
      for (const k of [...MACROS, ...OPTIONAL]) {
        const v = f._sum[k];
        if (v != null) a.sums[k] = a.sums[k].plus(v);
      }
      for (const k of OPTIONAL) a.known[k] += f._count[k];
    }
    for (const w of water) {
      const a = byDay.get(isoDate(w.date)!);
      const ml = w._sum.amountMl ?? 0;
      if (!a || ml <= 0) continue;
      a.water += ml;
      a.waterDays += 1;
    }

    const points = [...accs.values()].map((a): NutritionSummaryPoint => {
      const hasFood = a.entries > 0;
      const opt = (k: (typeof OPTIONAL)[number], v: (x: Dec) => number) => (a.known[k] ? v(a.sums[k]) : null);
      const shape = (v: (x: Dec) => number): SummaryNutrients => ({
        energyKcal: v(a.sums.energyKcal), proteinG: v(a.sums.proteinG), carbsG: v(a.sums.carbsG), fatG: v(a.sums.fatG),
        fiberG: opt('fiberG', v), sugarG: opt('sugarG', v), sodiumMg: opt('sodiumMg', v),
      });
      const avgKcal = hasFood ? a.sums.energyKcal.div(a.foodDays) : null;
      const avgWater = a.waterDays ? new D(a.water).div(a.waterDays) : null;
      const adherence: NutritionAdherence = {
        kcalPct: pct(avgKcal, kcal),
        // From the unrounded per-logged-day average, HALF_UP to one decimal.
        proteinPct: pct(hasFood ? a.sums.proteinG.div(a.foodDays) : null, proteinT),
        carbsPct: pct(hasFood ? a.sums.carbsG.div(a.foodDays) : null, carbsT),
        fatPct: pct(hasFood ? a.sums.fatG.div(a.foodDays) : null, fatT),
        waterPct: pct(avgWater, waterTarget),
      };
      const anyPct = Object.values(adherence).some((v) => v != null);
      return {
        period: a.period,
        daysInPeriod: a.days,
        daysLogged: a.foodDays,
        totals: hasFood ? shape(r1) : null,
        avgPerLoggedDay: hasFood ? shape((x) => r1(x.div(a.foodDays))) : null,
        waterMl: a.waterDays ? a.water : null,
        waterDaysLogged: a.waterDays,
        avgWaterMlPerLoggedDay: avgWater ? avgWater.toDecimalPlaces(0, HALF_UP).toNumber() : null,
        adherence: anyPct ? adherence : null,
        entryCount: a.entries,
        entriesMissing: { fiberG: a.entries - a.known.fiberG, sugarG: a.entries - a.known.sugarG, sodiumMg: a.entries - a.known.sodiumMg },
      };
    });

    return { from: q.from, to: q.to, bucket, timezone: profile?.timezone ?? DEFAULT_TZ, targets, points };
  }
}
