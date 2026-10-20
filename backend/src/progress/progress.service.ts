import { HttpStatus, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { decodeCursor, encodeCursor, invalidCursor } from '../common/cursor';
import { isoDate, num, parseIsoDate } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
import { dateRange, localDate, nextDate, prevDate, startOfLocalDay } from '../common/time';
import type { BodyMeasurement } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { BodyMeasurementDto, MEASUREMENT_FIELDS, MeasurementListQuery, SummaryQuery } from './progress.dto';

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

const toView = (m: BodyMeasurement): MeasurementView => ({
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

function measurementData(dto: BodyMeasurementDto) {
  const values = Object.fromEntries(MEASUREMENT_FIELDS.map((f) => [f, dto[f] ?? null]));
  if (MEASUREMENT_FIELDS.every((f) => values[f] === null)) {
    throw invalid({ date: 'add at least one measurement alongside the date' });
  }
  return { date: parseIsoDate(dto.date)!, ...values };
}

@Injectable()
export class ProgressService {
  constructor(private readonly prisma: PrismaService) {}

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
    return { items: page.map(toView), nextCursor: rows.length > limit && last ? encodeCursor({ d: isoDate(last.date)!, id: last.id }) : null };
  }

  async get(userId: string, id: string): Promise<MeasurementView> {
    const row = await this.prisma.bodyMeasurement.findFirst({ where: { id, userId } });
    if (!row) throw notFound('Measurement');
    return toView(row);
  }

  async create(userId: string, dto: BodyMeasurementDto): Promise<MeasurementView> {
    return toView(await this.prisma.bodyMeasurement.create({ data: { userId, ...measurementData(dto) } }));
  }

  /** Full replacement: an omitted measurement becomes null. */
  async update(userId: string, id: string, dto: BodyMeasurementDto): Promise<MeasurementView> {
    const data = measurementData(dto);
    const { count } = await this.prisma.bodyMeasurement.updateMany({ where: { id, userId }, data });
    if (count !== 1) throw notFound('Measurement');
    return this.get(userId, id);
  }

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
    const days = dateRange(q.from, q.to);
    if (days.length > MAX_SUMMARY_DAYS) throw invalid({ to: `the range can cover at most ${MAX_SUMMARY_DAYS} days` });

    const profile = await this.prisma.userProfile.findUnique({ where: { userId }, select: { timezone: true } });
    const tz = profile?.timezone ?? 'Asia/Kolkata';
    const start = startOfLocalDay(q.from, tz);
    const end = startOfLocalDay(nextDate(q.to), tz);

    const [measurements, sessions, foods, awards] = await Promise.all([
      this.prisma.bodyMeasurement.findMany({
        where: { userId, date: { gte: parseIsoDate(q.from)!, lte: parseIsoDate(q.to)! }, weightKg: { not: null } },
        orderBy: [{ date: 'asc' }, { createdAt: 'asc' }, { id: 'asc' }],
        select: { date: true, weightKg: true },
      }),
      this.prisma.workoutSession.findMany({
        where: { userId, status: 'completed', completedAt: { gte: start, lt: end } },
        select: { completedAt: true, activeSeconds: true },
      }),
      this.prisma.foodLog.groupBy({
        by: ['date'],
        where: { userId, date: { gte: parseIsoDate(q.from)!, lte: parseIsoDate(q.to)! } },
        _sum: { energyKcal: true },
      }),
      this.prisma.xPTransaction.findMany({
        where: { userId, sourceType: 'workout_session', createdAt: { gte: start, lt: end } },
        select: { id: true, amount: true, createdAt: true },
      }),
    ]);
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
      days.map((d) => [d, { date: d, weightKg: null, workoutsCompleted: 0, activeMinutes: 0, energyKcal: null, xpEarned: 0 }]),
    );
    for (const m of measurements) points.get(isoDate(m.date)!)!.weightKg = num(m.weightKg); // later rows win
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
      const p = points.get(localDate(a.createdAt, tz));
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
    const done = new Set(rows.map((r) => localDate(r.completedAt!, tz)));
    let day = done.has(today) ? today : prevDate(today);
    let count = 0;
    while (done.has(day)) {
      count += 1;
      day = prevDate(day);
    }
    return count;
  }
}
