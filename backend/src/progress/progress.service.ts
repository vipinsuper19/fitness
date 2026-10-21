import { HttpStatus, Injectable } from '@nestjs/common';

import { AchievementsService } from '../achievements/achievements.service';
import { EvaluatesAchievements } from '../achievements/evaluates-achievements.decorator';
import { ApiError } from '../common/api-error';
import { decodeCursor, encodeCursor, invalidCursor } from '../common/cursor';
import { isoDate, num, parseIsoDate } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
import { currentStreak } from '../common/streak';
import { dateRange, daySpan, localDate, nextDate, prevDate, startOfLocalDay } from '../common/time';
import type { BodyMeasurement } from '../generated/prisma/client';
import type { MeasurementField } from './progress.dto';
import { PrismaService } from '../prisma/prisma.service';
import { BodyMeasurementDto, BodyMeasurementUpdateDto, MEASUREMENT_DECIMALS, MEASUREMENT_FIELDS, MeasurementListQuery, SummaryQuery } from './progress.dto';

export interface MeasurementView {
  id: string;
  date: string;
  weightKg: number | null;
  bodyFatPct: number | null;
  waistCm: number | null;
  chestCm: number | null;
  hipCm: number | null;
  armCm: number | null;
  thighCm: number | null;
  createdAt: string;
}

export interface ProgressPoint {
  date: string;
  weightKg: number | null;
  bodyFatPct: number | null;
  waistCm: number | null;
  waterMl: number | null;
  workoutsCompleted: number;
  activeMinutes: number;
  energyKcal: number | null;
  xpEarned: number;
}

export interface ProgressSummary {
  from: string;
  to: string;
  points: ProgressPoint[];
  streakDays: number;
}

/** Longest range for one summary request (a year, leap-safe). */
export const MAX_SUMMARY_DAYS = 366;
/** How far back the streak is followed. */
const STREAK_LOOKBACK_DAYS = 400;

export const toMeasurementView = (m: BodyMeasurement): MeasurementView => ({
  id: m.id,
  date: isoDate(m.date)!,
  weightKg: num(m.weightKg),
  bodyFatPct: num(m.bodyFatPct),
  waistCm: num(m.waistCm),
  chestCm: num(m.chestCm),
  hipCm: num(m.hipCm),
  armCm: num(m.armCm),
  thighCm: num(m.thighCm),
  createdAt: m.createdAt.toISOString(),
});

const invalid = (fields: Record<string, string>) =>
  new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields });

/** Half-up rounding on the decimal value (72.25 -> 72.3), tolerant of binary float noise. */
export function roundHalfUp(v: number, places: number): number {
  const f = 10 ** places;
  return Math.round(Number((Math.abs(v) * f).toFixed(6))) / f * Math.sign(v);
}

const stored = (f: MeasurementField, v: number | null | undefined) => (v == null ? v : roundHalfUp(v, MEASUREMENT_DECIMALS[f]));

function measurementData(dto: BodyMeasurementDto) {
  const values = Object.fromEntries(MEASUREMENT_FIELDS.map((f) => [f, stored(f, dto[f]) ?? null]));
  if (MEASUREMENT_FIELDS.every((f) => values[f] === null)) {
    throw invalid({ date: 'add at least one measurement alongside the date' });
  }
  return { date: parseIsoDate(dto.date)!, ...values };
}

@Injectable()
export class ProgressService {
  constructor(
    private readonly prisma: PrismaService,
    readonly achievements: AchievementsService,
  ) {}

  async list(userId: string, q: MeasurementListQuery): Promise<{ items: MeasurementView[]; nextCursor: string | null }> {
    const limit = q.limit ?? 20;
    if (q.from && q.to && q.from > q.to) throw invalid({ from: 'from must be on or before to' });
    const c = decodeCursor(q.cursor, ['d', 'id'] as const);
    if (c && (!/^\d{4}-\d{2}-\d{2}$/.test(c.d) || isoDate(parseIsoDate(c.d)) !== c.d)) throw invalidCursor();
    const rows = await this.prisma.bodyMeasurement.findMany({
      where: {
        userId,
        date: { ...(q.from ? { gte: parseIsoDate(q.from)! } : {}), ...(q.to ? { lte: parseIsoDate(q.to)! } : {}) },
        ...(c ? { OR: [{ date: { lt: parseIsoDate(c.d)! } }, { date: parseIsoDate(c.d)!, id: { lt: c.id } }] } : {}),
      },
      orderBy: [{ date: 'desc' }, { id: 'desc' }],
      take: limit + 1,
    });
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    return { items: page.map(toMeasurementView), nextCursor: rows.length > limit && last ? encodeCursor({ d: isoDate(last.date)!, id: last.id }) : null };
  }

  async get(userId: string, id: string): Promise<MeasurementView> {
    const row = await this.prisma.bodyMeasurement.findFirst({ where: { id, userId } });
    if (!row) throw notFound('Measurement');
    return toMeasurementView(row);
  }

  @EvaluatesAchievements()
  async create(userId: string, dto: BodyMeasurementDto): Promise<MeasurementView> {
    return toMeasurementView(await this.prisma.bodyMeasurement.create({ data: { userId, ...measurementData(dto) } }));
  }

  /** Partial update: a missing field stays unchanged, null clears it; at least one measurement must remain. */
  @EvaluatesAchievements()
  async update(userId: string, id: string, dto: BodyMeasurementUpdateDto): Promise<MeasurementView> {
    return this.prisma.$transaction(async (tx) => {
      const row = await tx.bodyMeasurement.findFirst({ where: { id, userId } });
      if (!row) throw notFound('Measurement');
      if (!MEASUREMENT_FIELDS.some((f) => dto[f] !== undefined) && dto.date === undefined) {
        throw invalid({ body: 'send at least one field to change' });
      }
      const data: Record<string, unknown> = {};
      for (const f of MEASUREMENT_FIELDS) if (dto[f] !== undefined) data[f] = stored(f, dto[f]);
      if (dto.date !== undefined) data.date = parseIsoDate(dto.date)!;
      if (MEASUREMENT_FIELDS.every((f) => (f in data ? data[f] : row[f]) === null)) {
        throw invalid(Object.fromEntries(MEASUREMENT_FIELDS.filter((f) => f in data).map((f) => [f, 'the entry must keep at least one measurement'])));
      }
      const { count } = await tx.bodyMeasurement.updateMany({ where: { id, userId }, data });
      if (count !== 1) throw notFound('Measurement');
      return toMeasurementView((await tx.bodyMeasurement.findFirst({ where: { id, userId } }))!);
    });
  }

  @EvaluatesAchievements()
  async remove(userId: string, id: string): Promise<void> {
    const { count } = await this.prisma.bodyMeasurement.deleteMany({ where: { id, userId } });
    if (count !== 1) throw notFound('Measurement');
  }

  /**
   * One point per local day (profile timezone). Missing data is null, never 0:
   * weight is the day's latest measured weight, energy the day's logged kcal.
   * xpEarned counts workout awards on that day that were not later reversed.
   */
  async summary(userId: string, q: SummaryQuery, now = new Date()): Promise<ProgressSummary> {
    if (q.from > q.to) throw invalid({ from: 'from must be on or before to' });
    if (daySpan(q.from, q.to) > MAX_SUMMARY_DAYS) throw invalid({ to: `the range can cover at most ${MAX_SUMMARY_DAYS} days` });
    const days = dateRange(q.from, q.to, MAX_SUMMARY_DAYS);

    const profile = await this.prisma.userProfile.findUnique({ where: { userId }, select: { timezone: true } });
    const tz = profile?.timezone ?? 'Asia/Kolkata';
    const start = startOfLocalDay(q.from, tz);
    const end = startOfLocalDay(nextDate(q.to), tz);

    const [measurements, sessions, foods, water] = await Promise.all([
      this.prisma.bodyMeasurement.findMany({
        where: {
          userId,
          date: { gte: parseIsoDate(q.from)!, lte: parseIsoDate(q.to)! },
          OR: [{ weightKg: { not: null } }, { bodyFatPct: { not: null } }, { waistCm: { not: null } }],
        },
        orderBy: [{ date: 'asc' }, { createdAt: 'asc' }, { id: 'asc' }],
        select: { date: true, weightKg: true, bodyFatPct: true, waistCm: true },
      }),
      this.prisma.workoutSession.findMany({
        where: { userId, status: 'completed', completedAt: { gte: start, lt: end } },
        select: { id: true, completedAt: true, activeSeconds: true },
      }),
      this.prisma.foodLog.groupBy({
        by: ['date'],
        where: { userId, date: { gte: parseIsoDate(q.from)!, lte: parseIsoDate(q.to)! } },
        _sum: { energyKcal: true },
      }),
      this.prisma.waterLog.groupBy({
        by: ['date'],
        where: { userId, date: { gte: parseIsoDate(q.from)!, lte: parseIsoDate(q.to)! } },
        _sum: { amountMl: true },
      }),
    ]);
    // XP is attributed to the day the session was completed (not when the award row was written).
    const completedDay = new Map(sessions.map((s) => [s.id, localDate(s.completedAt!, tz)]));
    const awards = sessions.length
      ? await this.prisma.xPTransaction.findMany({
          where: { userId, sourceType: 'workout_session', sourceId: { in: sessions.map((s) => s.id) } },
          select: { id: true, amount: true, sourceId: true },
        })
      : [];
    const reversed = new Set(
      awards.length
        ? (
            await this.prisma.xPTransaction.findMany({
              where: { userId, sourceType: 'adjustment', sourceId: { in: awards.map((a) => a.id) } },
              select: { sourceId: true },
            })
          ).map((r) => r.sourceId)
        : [],
    );

    const points = new Map<string, ProgressPoint>(
      days.map((d) => [d, { date: d, weightKg: null, bodyFatPct: null, waistCm: null, waterMl: null, workoutsCompleted: 0, activeMinutes: 0, energyKcal: null, xpEarned: 0 }]),
    );
    // Several entries on one date: per series, the latest entry that day with a value wins
    // (createdAt, then id), so rows are applied oldest first and later ones overwrite.
    for (const m of measurements) {
      const p = points.get(isoDate(m.date)!)!;
      if (m.weightKg != null) p.weightKg = num(m.weightKg);
      if (m.bodyFatPct != null) p.bodyFatPct = num(m.bodyFatPct);
      if (m.waistCm != null) p.waistCm = num(m.waistCm);
    }
    for (const w of water) {
      const p = points.get(isoDate(w.date)!);
      if (p) p.waterMl = w._sum.amountMl ?? null;
    }
    const activeSeconds = new Map<string, number>();
    for (const s of sessions) {
      const d = localDate(s.completedAt!, tz);
      const p = points.get(d);
      if (!p) continue;
      p.workoutsCompleted += 1;
      activeSeconds.set(d, (activeSeconds.get(d) ?? 0) + s.activeSeconds);
    }
    for (const [d, secs] of activeSeconds) points.get(d)!.activeMinutes = Math.floor(secs / 60);
    for (const f of foods) {
      const p = points.get(isoDate(f.date)!);
      if (p) p.energyKcal = Math.round((num(f._sum.energyKcal) ?? 0) * 10) / 10;
    }
    for (const a of awards) {
      if (reversed.has(a.id)) continue;
      const p = points.get(completedDay.get(a.sourceId!)!);
      if (p) p.xpEarned += a.amount;
    }

    return { from: q.from, to: q.to, points: days.map((d) => points.get(d)!), streakDays: await this.streak(userId, tz, now) };
  }

  /**
   * Consecutive local days with a completed workout, ending today. If today
   * has none yet, the streak ending yesterday is still alive and is returned.
   */
  private async streak(userId: string, tz: string, now: Date): Promise<number> {
    const today = localDate(now, tz);
    let first = today;
    for (let i = 0; i < STREAK_LOOKBACK_DAYS; i += 1) first = prevDate(first);
    const rows = await this.prisma.workoutSession.findMany({
      where: { userId, status: 'completed', completedAt: { gte: startOfLocalDay(first, tz) } },
      select: { completedAt: true },
    });
    return currentStreak(new Set(rows.map((r) => localDate(r.completedAt!, tz))), today);
  }
}
