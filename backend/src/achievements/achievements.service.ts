import { Inject, Injectable, Logger, OnModuleDestroy } from '@nestjs/common';

import { ENV } from '../config/config.module';
import type { Env } from '../config/env';
import { localDate, nextDate, startOfLocalDay } from '../common/time';
import type { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { loadResolver } from '../targets/target-history';
import {
  BADGES, CATALOG_VERSION, cappedAward, challengeCount, DEFAULT_TIMEZONE, DEFAULT_WATER_GOAL_ML, Facts, nextStatus, templateByCode, weekStart,
  counts,
} from './achievements.rules';

type Tx = Prisma.TransactionClient;
const day = (d: Date) => d.toISOString().slice(0, 10);

export interface EvaluationResult {
  badgesAwarded: string[];
  challengesCompleted: { id: string; xpAwarded: number }[];
  challengesExpired: string[];
}

/**
 * Server-side awarding for badges and challenges (docs/specs/badges-challenges.md).
 * - Rules read existing data; nothing is ever revoked.
 * - Every award is insert-first on a unique key (or a guarded status update) and writes
 *   its XP ledger row in the same transaction, only when the amount is > 0.
 * - Per-user work is serialised with a transaction-scoped advisory lock, so concurrent
 *   evaluations and retries converge on one award.
 */
/** A badge as the API shows it once earned (contract `Badge`, progress null). */
export interface EarnedBadge {
  code: string; title: string; description: string; family: string; bonusXp: number; earnedAt: string; progress: null;
}

@Injectable()
export class AchievementsService implements OnModuleDestroy {
  private readonly logger = new Logger('Achievements');
  private readonly running = new Map<string, Promise<void>>();
  private readonly rerun = new Set<string>();

  constructor(
    private readonly prisma: PrismaService,
    @Inject(ENV) private readonly env: Pick<Env, 'ACHIEVEMENTS_HOOKS'>,
  ) {}

  /**
   * Call after a relevant write has committed. Never throws and never delays the
   * caller: evaluation runs on a later tick, coalesced per user, and failures are
   * only logged (the next write or read re-evaluates).
   */
  afterWrite(userId: string): void {
    if (this.env.ACHIEVEMENTS_HOOKS === 'off') return;
    if (this.running.has(userId)) {
      this.rerun.add(userId);
      return;
    }
    const job = (async () => {
      await new Promise((resolve) => setImmediate(resolve));
      do {
        this.rerun.delete(userId);
        try {
          await this.evaluate(userId);
        } catch (e) {
          this.logger.warn(`evaluate failed userId=${userId}: ${e instanceof Error ? e.message : String(e)}`);
        }
      } while (this.rerun.has(userId));
    })().finally(() => this.running.delete(userId));
    this.running.set(userId, job);
  }

  /**
   * Evaluates inside the request, after the caller's write has committed, and returns
   * the badges this evaluation awarded (earned views, progress null), for responses
   * that report `newBadges`. Never throws: a failure is logged and falls back to the
   * background evaluation, and the next read heals. Off when ACHIEVEMENTS_HOOKS=off.
   */
  async awardNow(userId: string): Promise<EarnedBadge[]> {
    if (this.env.ACHIEVEMENTS_HOOKS === 'off') return [];
    try {
      const { badgesAwarded } = await this.evaluate(userId);
      if (!badgesAwarded.length) return [];
      const rows = await this.prisma.userBadge.findMany({ where: { userId, badgeCode: { in: badgesAwarded } } });
      const at = new Map(rows.map((r) => [r.badgeCode, r.earnedAt]));
      return BADGES.filter((b) => at.has(b.code)).map((b) => ({
        code: b.code, title: b.title, description: b.description, family: b.family, bonusXp: b.bonusXp,
        earnedAt: at.get(b.code)!.toISOString(), progress: null,
      }));
    } catch (e) {
      this.logger.warn(`awardNow failed userId=${userId}: ${e instanceof Error ? e.message : String(e)}`);
      this.afterWrite(userId);
      return [];
    }
  }

  /** Resolves when no background evaluation is pending (tests, shutdown). */
  async idle(): Promise<void> {
    while (this.running.size) await Promise.all([...this.running.values()]);
  }

  async onModuleDestroy(): Promise<void> {
    await this.idle();
  }

  /** Lazy release backfill: evaluates once per catalog version. Returns true if it ran. */
  async ensureBackfilled(userId: string, now = new Date()): Promise<boolean> {
    const profile = await this.prisma.userProfile.findUnique({ where: { userId }, select: { badgesEvaluatedVersion: true } });
    if (profile && profile.badgesEvaluatedVersion >= CATALOG_VERSION) return false;
    await this.evaluate(userId, now);
    await this.prisma.userProfile.updateMany({ where: { userId, badgesEvaluatedVersion: { lt: CATALOG_VERSION } }, data: { badgesEvaluatedVersion: CATALOG_VERSION } });
    return true;
  }

  /** Evaluates every badge and the user's active challenges. Idempotent; safe to retry or run concurrently. */
  async evaluate(userId: string, now = new Date()): Promise<EvaluationResult> {
    return (await this.evaluateWithFacts(userId, now)).result;
  }

  /** Same as evaluate(), also returning the facts it used (the API reports badge progress from them). */
  async evaluateWithFacts(userId: string, now = new Date()): Promise<{ result: EvaluationResult; facts: Facts }> {
    const facts = await this.facts(userId, now);
    const badgesAwarded = await this.awardBadges(userId, facts);
    const challenges = await this.updateChallenges(userId, facts, now);
    return { result: { badgesAwarded, ...challenges }, facts };
  }

  /**
   * Loads everything the rules read. Client-dated rows are filtered by the backdating rule.
   * TODO(badges): this reads the user's full history on every evaluation (fine at MVP scale).
   * Aggregate the facts in SQL instead (per-day counts/sums with the backdating filter in the
   * WHERE clause, COUNT/SUM for totals) and bound date scans to the windows the rules need.
   */
  async facts(userId: string, now = new Date()): Promise<Facts> {
    const db = this.prisma;
    const profile = await db.userProfile.findUnique({ where: { userId }, select: { timezone: true } });
    const tz = profile?.timezone ?? DEFAULT_TIMEZONE;
    const today = localDate(now, tz);
    const ok = (r: { date: Date; createdAt: Date; dateChangedAt?: Date | null }) => counts(day(r.date), r.dateChangedAt ?? r.createdAt, today, tz);

    const sessions = await db.workoutSession.findMany({ where: { userId, status: 'completed', completedAt: { not: null } }, select: { completedAt: true, activeSeconds: true } });
    const completedSets = await db.exerciseSetLog.count({ where: { userId, completed: true, session: { status: 'completed' } } });
    const xp = await db.xPTransaction.aggregate({ where: { userId, sourceType: { in: ['workout_session', 'adjustment', 'session_correction'] } }, _sum: { amount: true } });
    const food = await db.foodLog.findMany({ where: { userId }, select: { date: true, createdAt: true } });
    const water = await db.waterLog.findMany({ where: { userId }, select: { date: true, createdAt: true, dateChangedAt: true, amountMl: true } });
    const measurements = await db.bodyMeasurement.findMany({ where: { userId }, select: { date: true, createdAt: true, dateChangedAt: true } });

    const targetsOn = await loadResolver(db, userId, today);
    const waterByDate = new Map<string, number>();
    for (const w of water.filter(ok)) waterByDate.set(day(w.date), (waterByDate.get(day(w.date)) ?? 0) + w.amountMl);
    return {
      tz,
      today,
      sessions: sessions.map((s) => ({ completedAt: s.completedAt!, activeSeconds: s.activeSeconds })),
      completedSets,
      coreXp: xp._sum.amount ?? 0,
      foodDates: [...new Set(food.filter(ok).map((r) => day(r.date)))],
      waterByDate,
      waterGoalOn: (date) => targetsOn(date).waterMl ?? DEFAULT_WATER_GOAL_ML,
      measurementDates: [...new Set(measurements.filter(ok).map((r) => day(r.date)))],
    };
  }

  async lock(tx: Tx, userId: string): Promise<void> {
    await tx.$queryRaw`SELECT pg_advisory_xact_lock(hashtextextended(${`achievements:${userId}`}, 0))::text`;
  }

  private async awardBadges(userId: string, f: Facts): Promise<string[]> {
    const have = new Set((await this.prisma.userBadge.findMany({ where: { userId }, select: { badgeCode: true } })).map((b) => b.badgeCode));
    const due = BADGES.filter((b) => !have.has(b.code) && (({ current, target }) => current >= target)(b.progress(f)));
    if (!due.length) return [];
    return this.prisma.$transaction(async (tx) => {
      await this.lock(tx, userId);
      const awarded: string[] = [];
      for (const b of due) {
        const rows = await tx.$queryRaw<{ id: string }[]>`
          INSERT INTO user_badges (id, user_id, badge_code, catalog_version)
          VALUES (gen_random_uuid(), ${userId}::uuid, ${b.code}, ${CATALOG_VERSION})
          ON CONFLICT (user_id, badge_code) DO NOTHING
          RETURNING id::text`;
        if (!rows.length) continue;
        awarded.push(b.code);
        if (b.bonusXp > 0) {
          await tx.xPTransaction.create({ data: { userId, sourceType: 'badge', sourceId: rows[0]!.id, amount: b.bonusXp, reason: `Badge: ${b.title}` } });
        }
      }
      return awarded;
    });
  }

  /** Challenge bonus XP already awarded in the ISO week (Monday start, profile tz) containing `now`. */
  private async awardedThisWeek(tx: Tx, userId: string, f: Facts): Promise<number> {
    const monday = weekStart(f.today);
    let end = monday;
    for (let i = 0; i < 7; i += 1) end = nextDate(end);
    const agg = await tx.userChallenge.aggregate({
      where: { userId, status: 'completed', completedAt: { gte: startOfLocalDay(monday, f.tz), lt: startOfLocalDay(end, f.tz) } },
      _sum: { xpAwarded: true },
    });
    return agg._sum.xpAwarded ?? 0;
  }

  private async updateChallenges(userId: string, f: Facts, now: Date): Promise<Omit<EvaluationResult, 'badgesAwarded'>> {
    if (!(await this.prisma.userChallenge.count({ where: { userId, status: 'active' } }))) return { challengesCompleted: [], challengesExpired: [] };
    return this.prisma.$transaction(async (tx) => {
      await this.lock(tx, userId);
      return this.applyChallenges(tx, userId, f, now);
    });
  }

  /**
   * Re-evaluates the user's active challenges inside a caller's transaction that already
   * holds this user's lock (e.g. leave), so a target reached just now completes first.
   */
  async settleChallenges(tx: Tx, userId: string, now = new Date()): Promise<Omit<EvaluationResult, 'badgesAwarded'>> {
    return this.applyChallenges(tx, userId, await this.facts(userId, now), now);
  }

  private async applyChallenges(tx: Tx, userId: string, f: Facts, now: Date): Promise<Omit<EvaluationResult, 'badgesAwarded'>> {
    const out: Omit<EvaluationResult, 'badgesAwarded'> = { challengesCompleted: [], challengesExpired: [] };
    {
      const active = await tx.userChallenge.findMany({ where: { userId, status: 'active' }, orderBy: [{ joinedAt: 'asc' }, { id: 'asc' }] });
      for (const c of active) {
        const tpl = templateByCode(c.templateCode);
        if (!tpl) {
          this.logger.warn(`unknown challenge template ${c.templateCode} id=${c.id}`);
          continue;
        }
        const state = {
          templateCode: c.templateCode, target: c.target, windowStart: day(c.windowStart), windowEnd: c.windowEnd ? day(c.windowEnd) : null,
          joinDate: day(c.joinDate), joinedAt: c.joinedAt, timezone: c.timezone, waterGoalMl: c.waterGoalMl,
        };
        const today = localDate(now, c.timezone);
        const count = challengeCount(state, f, today);
        const status = nextStatus(count, c.target, state.windowEnd, today);
        if (status === 'completed') {
          const amount = cappedAward(tpl.bonusXp, await this.awardedThisWeek(tx, userId, f));
          const done = await tx.userChallenge.updateMany({
            where: { id: c.id, status: 'active' },
            data: { status: 'completed', progress: c.target, completedAt: now, xpAwarded: amount },
          });
          if (done.count !== 1) continue;
          if (amount > 0) await tx.xPTransaction.create({ data: { userId, sourceType: 'challenge', sourceId: c.id, amount, reason: `Challenge: ${tpl.title}` } });
          out.challengesCompleted.push({ id: c.id, xpAwarded: amount });
        } else if (status === 'expired') {
          await tx.userChallenge.updateMany({ where: { id: c.id, status: 'active' }, data: { status: 'expired', progress: Math.min(count, c.target) } });
          out.challengesExpired.push(c.id);
        } else if (count !== c.progress) {
          await tx.userChallenge.updateMany({ where: { id: c.id, status: 'active' }, data: { progress: count } });
        }
      }
    }
    return out;
  }
}
