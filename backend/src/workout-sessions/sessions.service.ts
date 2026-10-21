import { HttpStatus, Injectable } from '@nestjs/common';

import { AchievementsService } from '../achievements/achievements.service';
import { EvaluatesAchievements } from '../achievements/evaluates-achievements.decorator';
import { ApiError } from '../common/api-error';
import { decodeTimeCursor, encodeCursor } from '../common/cursor';
import { num } from '../common/decimal';
import { notFound } from '../common/id-param.pipe';
import { clientTime, nextDate, startOfLocalDay } from '../common/time';
import { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import type { XpBalance } from '../xp/xp.rules';
import { workoutXp } from '../xp/xp.rules';
import { XpService } from '../xp/xp.service';
import type { HistoryQuery, SessionCompleteDto, SessionStartDto, SessionUpdateDto, SetLogUpsertDto } from './sessions.dto';

type Tx = Prisma.TransactionClient;

/** Longest session we count; guards against a device left running for days. */
export const MAX_ACTIVE_SECONDS = 24 * 60 * 60;
/** Offline starts may be back-dated by at most a day. */
export const MAX_START_AGE_MS = 24 * 60 * 60 * 1000;

export const sessionInclude = {
  planVersion: { select: { planId: true, version: true } },
  sets: { orderBy: [{ setIndex: 'asc' }, { loggedAt: 'asc' }, { id: 'asc' }] },
} satisfies Prisma.WorkoutSessionInclude;
type SessionRow = Prisma.WorkoutSessionGetPayload<{ include: typeof sessionInclude }>;
type SetRow = SessionRow['sets'][number];

export interface SetLogView {
  id: string;
  clientSetId: string;
  exerciseId: string;
  setIndex: number;
  reps: number | null;
  weightKg: number | null;
  durationSeconds: number | null;
  distanceM: number | null;
  completed: boolean;
  loggedAt: string;
  updatedAt: string;
}

export interface SessionView {
  id: string;
  planId: string | null;
  planVersion: number | null;
  plannedWorkoutId: string | null;
  status: 'in_progress' | 'paused' | 'completed';
  startedAt: string;
  completedAt: string | null;
  activeSeconds: number;
  perceivedDifficulty: number | null;
  notes: string | null;
  sets: SetLogView[];
}

export interface CompleteResult {
  session: SessionView;
  xpAwarded: number;
  xpBalance: XpBalance;
}

const toSetView = (s: SetRow): SetLogView => ({
  id: s.id,
  clientSetId: s.clientSetId,
  exerciseId: s.exerciseId,
  setIndex: s.setIndex,
  reps: s.reps,
  weightKg: num(s.weightKg),
  durationSeconds: s.durationSeconds,
  distanceM: num(s.distanceM),
  completed: s.completed,
  loggedAt: s.loggedAt.toISOString(),
  updatedAt: s.updatedAt.toISOString(),
});

/** Accumulated active time plus the running segment, if any (never paused time). */
function liveActiveSeconds(s: { activeSeconds: number; activeSince: Date | null }, now: Date): number {
  const running = s.activeSince ? Math.max(0, Math.floor((now.getTime() - s.activeSince.getTime()) / 1000)) : 0;
  return Math.min(MAX_ACTIVE_SECONDS, s.activeSeconds + running);
}

export function toSessionView(s: SessionRow, now = new Date()): SessionView {
  return {
    id: s.id,
    planId: s.planVersion?.planId ?? null,
    planVersion: s.planVersion?.version ?? null,
    plannedWorkoutId: s.plannedWorkoutId,
    status: s.status,
    startedAt: s.startedAt.toISOString(),
    completedAt: s.completedAt?.toISOString() ?? null,
    activeSeconds: liveActiveSeconds(s, now),
    perceivedDifficulty: s.perceivedDifficulty,
    notes: s.notes,
    sets: s.sets.map(toSetView),
  };
}

const invalid = (field: string, message: string) =>
  new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields: { [field]: message } });
const sessionCompleted = () =>
  new ApiError(HttpStatus.CONFLICT, 'SESSION_COMPLETED', 'This workout is finished and can no longer be changed.');

@Injectable()
export class SessionsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly xp: XpService,
    readonly achievements: AchievementsService,
  ) {}

  async start(userId: string, dto: SessionStartDto): Promise<SessionView> {
    const now = new Date();
    const startedAt = clientTime(dto.startedAt, 'startedAt', now) ?? now;
    if (startedAt.getTime() < now.getTime() - MAX_START_AGE_MS) {
      throw invalid('startedAt', 'startedAt cannot be more than 24 hours ago');
    }
    let planVersionId: string | null = null;
    if (dto.plannedWorkoutId) {
      const pw = await this.prisma.plannedWorkout.findFirst({
        where: { id: dto.plannedWorkoutId, planVersion: { plan: { userId } } },
        select: { planVersion: { select: { id: true, version: true } } },
      });
      if (!pw) throw notFound('Planned workout');
      if (dto.planVersion !== undefined && dto.planVersion !== pw.planVersion.version) {
        throw invalid('planVersion', 'planVersion does not match the planned workout');
      }
      planVersionId = pw.planVersion.id;
    } else if (dto.planVersion !== undefined) {
      throw invalid('planVersion', 'planVersion needs plannedWorkoutId');
    }
    const row = await this.prisma.workoutSession.create({
      data: { userId, planVersionId, plannedWorkoutId: dto.plannedWorkoutId ?? null, startedAt, activeSince: startedAt },
      include: sessionInclude,
    });
    return toSessionView(row, now);
  }

  async get(userId: string, id: string, db: PrismaService | Tx = this.prisma): Promise<SessionView> {
    const row = await db.workoutSession.findFirst({ where: { id, userId }, include: sessionInclude });
    if (!row) throw notFound('Session');
    return toSessionView(row);
  }

  /** Pause, resume or edit notes. Pausing banks the running segment; paused time never counts. */
  async update(userId: string, id: string, dto: SessionUpdateDto): Promise<SessionView> {
    return this.prisma.$transaction(async (tx) => {
      const s = await this.lock(tx, userId, id);
      if (s.status === 'completed') throw sessionCompleted();
      const now = new Date();
      const data: Prisma.WorkoutSessionUpdateInput = {};
      if (dto.notes !== undefined) data.notes = dto.notes;
      if (dto.status === 'paused' && s.status === 'in_progress') {
        Object.assign(data, { status: 'paused', pausedAt: now, activeSince: null, activeSeconds: liveActiveSeconds(s, now) });
      } else if (dto.status === 'in_progress' && s.status === 'paused') {
        Object.assign(data, { status: 'in_progress', pausedAt: null, activeSince: now });
      }
      if (Object.keys(data).length) await tx.workoutSession.update({ where: { id }, data });
      return this.get(userId, id, tx);
    });
  }

  /**
   * Upsert by the device's clientSetId (unique per user). Re-sending the same
   * clientSetId replaces that set; using it for another session is 409.
   */
  @EvaluatesAchievements()
  async upsertSet(userId: string, sessionId: string, dto: SetLogUpsertDto): Promise<SetLogView> {
    const now = new Date();
    const loggedAt = clientTime(dto.loggedAt, 'loggedAt', now);
    const exercise = await this.prisma.exercise.findUnique({ where: { id: dto.exerciseId }, select: { id: true } });
    if (!exercise) throw invalid('exerciseId', 'exerciseId is not in the exercise catalog');
    const clientSetId = dto.clientSetId.toLowerCase();
    const fields = {
      exerciseId: exercise.id,
      setIndex: dto.setIndex,
      reps: dto.reps ?? null,
      weightKg: dto.weightKg ?? null,
      durationSeconds: dto.durationSeconds ?? null,
      distanceM: dto.distanceM ?? null,
      completed: dto.completed,
      ...(loggedAt ? { loggedAt } : {}),
    };
    try {
      return await this.prisma.$transaction(async (tx) => {
        const s = await this.lock(tx, userId, sessionId);
        if (s.status === 'completed') throw sessionCompleted();
        const existing = await tx.exerciseSetLog.findUnique({ where: { userId_clientSetId: { userId, clientSetId } } });
        if (existing && existing.sessionId !== sessionId) throw clientSetConflict();
        const row = existing
          ? await tx.exerciseSetLog.update({ where: { id: existing.id }, data: fields })
          : await tx.exerciseSetLog.create({ data: { userId, sessionId, clientSetId, ...fields } });
        return toSetView(row);
      });
    } catch (error) {
      // A concurrent first write of the same clientSetId to another session.
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') throw clientSetConflict();
      throw error;
    }
  }

  /**
   * Completes the session and awards XP in one transaction, under a row lock.
   * Completing again returns the original result: the award is looked up,
   * never recomputed or re-written.
   */
  @EvaluatesAchievements()
  async complete(userId: string, id: string, dto: SessionCompleteDto): Promise<CompleteResult> {
    return this.prisma.$transaction(async (tx) => {
      const s = await this.lock(tx, userId, id);
      if (s.status === 'completed') {
        const award = await this.xp.findAward(tx, userId, id);
        return { session: await this.get(userId, id, tx), xpAwarded: award?.amount ?? 0, xpBalance: await this.xp.balance(userId, tx) };
      }
      const now = new Date();
      const completedAt = clientTime(dto.completedAt, 'completedAt', now) ?? now;
      if (completedAt < s.startedAt) throw invalid('completedAt', 'completedAt must be after startedAt');
      const completedSets = await tx.exerciseSetLog.count({ where: { sessionId: id, completed: true } });
      if (completedSets === 0) {
        throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, 'SESSION_EMPTY', 'Log at least one completed set before finishing.');
      }
      const activeSeconds = liveActiveSeconds(s, completedAt);
      await tx.workoutSession.update({
        where: { id },
        data: {
          status: 'completed',
          completedAt,
          pausedAt: null,
          activeSince: null,
          activeSeconds,
          ...(dto.perceivedDifficulty !== undefined ? { perceivedDifficulty: dto.perceivedDifficulty } : {}),
          ...(dto.notes !== undefined ? { notes: dto.notes } : {}),
        },
      });
      const award = await this.xp.awardWorkout(tx, userId, id, workoutXp(completedSets, activeSeconds));
      return { session: await this.get(userId, id, tx), xpAwarded: award?.amount ?? 0, xpBalance: await this.xp.balance(userId, tx) };
    });
  }

  /**
   * Discards a session logged by mistake (the correction path). Its XP award,
   * if any, is reversed by a server-side adjustment; the award stays in the
   * ledger, so the session can never be awarded again.
   */
  @EvaluatesAchievements()
  async discard(userId: string, id: string): Promise<void> {
    await this.prisma.$transaction(async (tx) => {
      await this.lock(tx, userId, id);
      const award = await this.xp.findAward(tx, userId, id);
      if (award) await this.xp.reverse(tx, award, 'Workout discarded');
      await tx.workoutSession.delete({ where: { id } });
    });
  }

  async history(userId: string, q: HistoryQuery): Promise<{ items: SessionView[]; nextCursor: string | null }> {
    const limit = q.limit ?? 20;
    if (q.from && q.to && q.from > q.to) throw invalid('from', 'from must be on or before to');
    const after = decodeTimeCursor(q.cursor);
    const profile = await this.prisma.userProfile.findUnique({ where: { userId }, select: { timezone: true } });
    const tz = profile?.timezone ?? 'Asia/Kolkata';
    const range: Prisma.DateTimeNullableFilter = { not: null };
    if (q.from) range.gte = startOfLocalDay(q.from, tz);
    if (q.to) range.lt = startOfLocalDay(nextDate(q.to), tz);
    const rows = await this.prisma.workoutSession.findMany({
      where: {
        userId,
        status: 'completed',
        completedAt: range,
        ...(after ? { OR: [{ completedAt: { lt: after.t } }, { completedAt: after.t, id: { lt: after.id } }] } : {}),
      },
      orderBy: [{ completedAt: 'desc' }, { id: 'desc' }],
      take: limit + 1,
      include: sessionInclude,
    });
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    return {
      items: page.map((r) => toSessionView(r)),
      nextCursor: rows.length > limit && last?.completedAt ? encodeCursor({ t: last.completedAt.toISOString(), id: last.id }) : null,
    };
  }

  /** Owner-scoped row lock: serialises pause, set writes, completion and discard per session. */
  private async lock(tx: Tx, userId: string, id: string) {
    const locked = await tx.$queryRaw<{ id: string }[]>`
      SELECT id FROM workout_sessions WHERE id = ${id}::uuid AND user_id = ${userId}::uuid FOR UPDATE`;
    if (!locked.length) throw notFound('Session');
    return tx.workoutSession.findUniqueOrThrow({ where: { id } });
  }
}

function clientSetConflict(): ApiError {
  return new ApiError(HttpStatus.CONFLICT, 'CLIENT_SET_CONFLICT', 'This clientSetId was already used for a set in another workout.');
}
