import { HttpStatus, Injectable } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { decodeTimeCursor, encodeCursor } from '../common/cursor';
import { notFound } from '../common/id-param.pipe';
import { dateRange, daySpan, nextDate, startOfLocalDay } from '../common/time';
import { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { MAX_SUMMARY_DAYS } from './progress.service';
import type { ExerciseProgressQuery, ExerciseProgressListQuery } from './strength.dto';

/**
 * Strength progress (docs/specs/strength-progress.md). Read-only; every value is
 * aggregated in Postgres from the current set rows, so discards (deleted sessions)
 * drop out and corrections apply on the next read. Records are all-time.
 */

export type Bucket = 'day' | 'week' | 'month';

export interface PersonalRecords {
  maxWeightKg: number | null; maxWeightKgAchievedAt: string | null;
  maxReps: number | null; maxRepsAchievedAt: string | null;
  best1RmKg: number | null; best1RmKgAchievedAt: string | null;
  maxDurationSec: number | null; maxDurationSecAchievedAt: string | null;
  maxDistanceM: number | null; maxDistanceMAchievedAt: string | null;
}
export interface ExerciseProgressPoint {
  period: string; sessions: number; sets: number; volumeKg: number | null; totalReps: number | null;
  topSetWeightKg: number | null; best1RmKg: number | null; totalDurationSec: number | null; totalDistanceM: number | null;
  maxSetDurationSec: number | null; maxSetDistanceM: number | null;
}
export interface ExerciseProgressSummary {
  exerciseId: string; name: string; trackingType: string; sessions: number; lastPerformedAt: string; pr: PersonalRecords;
}

const invalid = (fields: Record<string, string>) => new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields });
const n = (v: unknown): number | null => (v === null || v === undefined ? null : Number(v));
const iso = (v: Date | null): string | null => v?.toISOString() ?? null;

/** Period key of a local date: itself, its Monday, or the 1st of its month; clipped to `from`. */
export function periodOf(date: string, bucket: Bucket, from: string): string {
  let key = date;
  if (bucket === 'week') {
    const d = new Date(`${date}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
    key = d.toISOString().slice(0, 10);
  } else if (bucket === 'month') key = `${date.slice(0, 7)}-01`;
  return key < from ? from : key;
}

/** Counting sets: completed sets in completed sessions of this user. */
const base = (userId: string) => Prisma.sql`
  FROM exercise_set_logs l JOIN workout_sessions w ON w.id = l.session_id
  WHERE w.user_id = ${userId}::uuid AND w.status = 'completed' AND w.completed_at IS NOT NULL AND l.completed`;

/** Per-set metric expressions shared by points and records (null when the set has no such value). */
const METRICS = Prisma.sql`
  CASE WHEN l.weight_kg > 0 AND l.reps >= 1 THEN l.weight_kg END AS w,
  CASE WHEN l.reps >= 1 THEN l.reps END AS r,
  CASE WHEN l.weight_kg > 0 AND l.reps BETWEEN 1 AND 12
       THEN ROUND(CASE WHEN l.reps = 1 THEN l.weight_kg ELSE l.weight_kg * (1 + l.reps / 30.0) END, 1) END AS e1rm,
  CASE WHEN l.duration_seconds > 0 THEN l.duration_seconds END AS dur,
  CASE WHEN l.distance_m > 0 THEN l.distance_m END AS dist`;

@Injectable()
export class StrengthService {
  constructor(private readonly prisma: PrismaService) {}

  private async range(userId: string, q: { from: string; to: string }) {
    if (q.from > q.to) throw invalid({ from: 'from must be on or before to' });
    if (daySpan(q.from, q.to) > MAX_SUMMARY_DAYS) throw invalid({ to: `the range can cover at most ${MAX_SUMMARY_DAYS} days` });
    const profile = await this.prisma.userProfile.findUnique({ where: { userId }, select: { timezone: true } });
    const tz = profile?.timezone ?? 'Asia/Kolkata';
    return { tz, start: startOfLocalDay(q.from, tz), end: startOfLocalDay(nextDate(q.to), tz) };
  }

  async list(userId: string, q: ExerciseProgressListQuery) {
    const { tz, start, end } = await this.range(userId, q);
    const limit = q.limit ?? 20;
    const after = decodeTimeCursor(q.cursor);
    const rows = await this.prisma.$queryRaw<{ exercise_id: string; sessions: number; last: Date }[]>`
      SELECT l.exercise_id::text AS exercise_id, COUNT(DISTINCT w.id)::int AS sessions, MAX(w.completed_at) AS last
      ${base(userId)} AND w.completed_at >= ${start} AND w.completed_at < ${end}
      GROUP BY l.exercise_id
      ${after ? Prisma.sql`HAVING (MAX(w.completed_at), l.exercise_id) < (${after.t}::timestamptz, ${after.id}::uuid)` : Prisma.empty}
      ORDER BY last DESC, l.exercise_id DESC
      LIMIT ${limit + 1}`;
    const page = rows.slice(0, limit);
    const ids = page.map((r) => r.exercise_id);
    const [exercises, records] = await Promise.all([
      this.prisma.exercise.findMany({ where: { id: { in: ids } }, select: { id: true, name: true, trackingType: true } }),
      this.records(userId, ids),
    ]);
    const ex = new Map(exercises.map((e) => [e.id, e]));
    const items: ExerciseProgressSummary[] = page.map((r) => ({
      exerciseId: r.exercise_id, name: ex.get(r.exercise_id)!.name, trackingType: ex.get(r.exercise_id)!.trackingType,
      sessions: r.sessions, lastPerformedAt: r.last.toISOString(), pr: records.get(r.exercise_id) ?? emptyRecords(),
    }));
    const last = page[page.length - 1];
    return {
      from: q.from, to: q.to, timezone: tz, items,
      nextCursor: rows.length > limit && last ? encodeCursor({ t: last.last.toISOString(), id: last.exercise_id }) : null,
    };
  }

  async detail(userId: string, exerciseId: string, q: ExerciseProgressQuery) {
    const exercise = await this.prisma.exercise.findUnique({ where: { id: exerciseId }, select: { id: true, name: true, trackingType: true } });
    if (!exercise) throw notFound('Exercise');
    const { tz, start, end } = await this.range(userId, q);
    const bucket: Bucket = q.bucket ?? 'day';
    const key =
      bucket === 'day' ? Prisma.sql`d` : bucket === 'week' ? Prisma.sql`date_trunc('week', d)::date` : Prisma.sql`date_trunc('month', d)::date`;
    const rows = await this.prisma.$queryRaw<Record<string, unknown>[]>`
      WITH s AS (
        SELECT w.id AS sid, (w.completed_at AT TIME ZONE ${tz})::date AS d, l.reps, l.weight_kg, l.duration_seconds, l.distance_m, ${METRICS}
        ${base(userId)} AND l.exercise_id = ${exerciseId}::uuid AND w.completed_at >= ${start} AND w.completed_at < ${end}
      )
      SELECT to_char(GREATEST(${key}, ${q.from}::date), 'YYYY-MM-DD') AS period,
        COUNT(DISTINCT sid)::int AS sessions, COUNT(*)::int AS sets,
        ROUND(SUM(weight_kg * reps) FILTER (WHERE weight_kg > 0 AND reps > 0), 2) AS volume,
        SUM(reps) FILTER (WHERE reps IS NOT NULL)::int AS total_reps,
        MAX(w) AS top, MAX(e1rm) AS e1rm,
        SUM(dur)::int AS tdur, ROUND(SUM(dist), 1) AS tdist, MAX(dur) AS mdur, MAX(dist) AS mdist
      FROM s GROUP BY 1`;
    const byPeriod = new Map(rows.map((r) => [r.period as string, r]));
    const periods = [...new Set(dateRange(q.from, q.to, MAX_SUMMARY_DAYS).map((d) => periodOf(d, bucket, q.from)))];
    const points: ExerciseProgressPoint[] = periods.map((period) => {
      const r = byPeriod.get(period);
      return {
        period, sessions: (r?.sessions as number) ?? 0, sets: (r?.sets as number) ?? 0,
        volumeKg: n(r?.volume), totalReps: n(r?.total_reps), topSetWeightKg: n(r?.top), best1RmKg: n(r?.e1rm),
        totalDurationSec: n(r?.tdur), totalDistanceM: n(r?.tdist), maxSetDurationSec: n(r?.mdur), maxSetDistanceM: n(r?.mdist),
      };
    });
    const pr = (await this.records(userId, [exerciseId])).get(exerciseId) ?? emptyRecords();
    return { exerciseId, name: exercise.name, trackingType: exercise.trackingType, from: q.from, to: q.to, bucket, timezone: tz, pr, points };
  }

  /** All-time records; each achievedAt is the earliest session that reached the (rounded) value. */
  private async records(userId: string, ids: string[]): Promise<Map<string, PersonalRecords>> {
    if (!ids.length) return new Map();
    const rows = await this.prisma.$queryRaw<Record<string, unknown>[]>`
      WITH s AS (
        SELECT l.exercise_id, w.completed_at AS at, ${METRICS}
        ${base(userId)} AND l.exercise_id = ANY(${ids}::uuid[])
      ), m AS (
        SELECT exercise_id, MAX(w) AS mw, MAX(r) AS mr, MAX(e1rm) AS me, MAX(dur) AS md, MAX(dist) AS mdi FROM s GROUP BY exercise_id
      )
      SELECT m.exercise_id::text AS id, m.mw, m.mr, m.me, m.md, m.mdi,
        MIN(s.at) FILTER (WHERE s.w = m.mw) AS aw, MIN(s.at) FILTER (WHERE s.r = m.mr) AS ar,
        MIN(s.at) FILTER (WHERE s.e1rm = m.me) AS ae, MIN(s.at) FILTER (WHERE s.dur = m.md) AS ad,
        MIN(s.at) FILTER (WHERE s.dist = m.mdi) AS adi
      FROM m JOIN s ON s.exercise_id = m.exercise_id
      GROUP BY m.exercise_id, m.mw, m.mr, m.me, m.md, m.mdi`;
    return new Map(
      rows.map((r) => [
        r.id as string,
        {
          maxWeightKg: n(r.mw), maxWeightKgAchievedAt: iso(r.aw as Date | null),
          maxReps: n(r.mr), maxRepsAchievedAt: iso(r.ar as Date | null),
          best1RmKg: n(r.me), best1RmKgAchievedAt: iso(r.ae as Date | null),
          maxDurationSec: n(r.md), maxDurationSecAchievedAt: iso(r.ad as Date | null),
          maxDistanceM: n(r.mdi), maxDistanceMAchievedAt: iso(r.adi as Date | null),
        },
      ]),
    );
  }
}

const emptyRecords = (): PersonalRecords => ({
  maxWeightKg: null, maxWeightKgAchievedAt: null, maxReps: null, maxRepsAchievedAt: null, best1RmKg: null, best1RmKgAchievedAt: null,
  maxDurationSec: null, maxDurationSecAchievedAt: null, maxDistanceM: null, maxDistanceMAchievedAt: null,
});
