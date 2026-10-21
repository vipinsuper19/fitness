import { HttpStatus, Injectable, Logger } from '@nestjs/common';

import { ApiError } from '../common/api-error';
import { decodeTimeCursor, encodeCursor } from '../common/cursor';
import { notFound } from '../common/id-param.pipe';
import { localDate, nextDate } from '../common/time';
import type { UserBadge, UserChallenge } from '../generated/prisma/client';
import { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import type { ChallengeJoinDto, ChallengeListQuery } from './achievements.dto';
import {
  BADGES, CATALOG_VERSION, CHALLENGE_TEMPLATES, ChallengeTemplateDef, DEFAULT_TIMEZONE, DEFAULT_WATER_GOAL_ML, Facts, MAX_ACTIVE_CHALLENGES, templateByCode,
  weekStart,
} from './achievements.rules';
import { AchievementsService } from './achievements.service';

const day = (d: Date) => d.toISOString().slice(0, 10);
const addDays = (date: string, n: number) => { let d = date; for (let i = 0; i < n; i += 1) d = nextDate(d); return d; };
const invalid = (fields: Record<string, string>) => new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', { fields });
const alreadyJoined = () => new ApiError(HttpStatus.CONFLICT, 'CHALLENGE_ALREADY_JOINED', 'You have already joined this challenge.');

export interface BadgeView {
  code: string; title: string; description: string; family: string; bonusXp: number; earnedAt: string | null;
  progress: { current: number; target: number } | null;
}
export interface ChallengeView {
  id: string; templateCode: string; type: 'weekly' | 'personal'; title: string; target: number; progress: number; unit: string;
  windowStart: string; windowEnd: string | null; joinDate: string; timezone: string; waterGoalMl: number | null;
  status: 'active' | 'completed' | 'expired' | 'left'; joinedAt: string; completedAt: string | null; leftAt: string | null; xpAwarded: number;
}

export const toChallengeView = (c: UserChallenge): ChallengeView => {
  const tpl = templateByCode(c.templateCode);
  return {
    id: c.id, templateCode: c.templateCode, type: c.type, title: tpl?.title ?? c.templateCode, target: c.target, progress: c.progress, unit: tpl?.unit ?? '',
    windowStart: day(c.windowStart), windowEnd: c.windowEnd ? day(c.windowEnd) : null, joinDate: day(c.joinDate), timezone: c.timezone, waterGoalMl: c.waterGoalMl,
    status: c.status, joinedAt: c.joinedAt.toISOString(), completedAt: c.completedAt?.toISOString() ?? null, leftAt: c.leftAt?.toISOString() ?? null, xpAwarded: c.xpAwarded,
  };
};
export const toExportedBadge = (b: UserBadge) => ({ code: b.badgeCode, earnedAt: b.earnedAt.toISOString() });
export const toTemplateView = (t: ChallengeTemplateDef) => ({
  code: t.code, type: t.type, title: t.title, description: t.description, metric: t.metric, unit: t.unit, targets: t.targets, deadlineDays: t.deadlineDays, bonusXp: t.bonusXp,
});

/** HTTP-facing badges & challenges. Reads re-evaluate first, so a background evaluation lost on a restart heals on read. */
@Injectable()
export class AchievementsApiService {
  private readonly logger = new Logger('Achievements');

  constructor(
    private readonly prisma: PrismaService,
    private readonly engine: AchievementsService,
  ) {}

  /** Evaluates (and marks the catalog version as evaluated); a failure is logged and the read still answers. */
  private async heal(userId: string, now: Date): Promise<Facts | null> {
    try {
      const { facts } = await this.engine.evaluateWithFacts(userId, now);
      await this.prisma.userProfile.updateMany({ where: { userId, badgesEvaluatedVersion: { lt: CATALOG_VERSION } }, data: { badgesEvaluatedVersion: CATALOG_VERSION } });
      return facts;
    } catch (e) {
      // Never fail the read: answer with the stored data (the next write or read heals).
      this.logger.warn(`evaluate on read failed userId=${userId}: ${e instanceof Error ? e.message : String(e)}`);
      return this.engine.facts(userId, now).catch(() => null);
    }
  }

  async listBadges(userId: string, now = new Date()): Promise<{ items: BadgeView[]; earnedCount: number }> {
    const facts = await this.heal(userId, now);
    const earned = new Map((await this.prisma.userBadge.findMany({ where: { userId } })).map((b) => [b.badgeCode, b]));
    const items = BADGES.map((b): BadgeView => {
      const row = earned.get(b.code);
      const p = facts ? b.progress(facts) : null;
      return {
        code: b.code, title: b.title, description: b.description, family: b.family, bonusXp: b.bonusXp,
        earnedAt: row?.earnedAt.toISOString() ?? null,
        progress: row || !p ? null : { current: Math.min(p.current, p.target), target: p.target },
      };
    });
    return { items, earnedCount: items.filter((i) => i.earnedAt).length };
  }

  templates() {
    return { items: CHALLENGE_TEMPLATES.map(toTemplateView) };
  }

  async listChallenges(userId: string, q: ChallengeListQuery, now = new Date()): Promise<{ items: ChallengeView[]; nextCursor: string | null }> {
    await this.heal(userId, now);
    const limit = q.limit ?? 20;
    const after = decodeTimeCursor(q.cursor);
    const rows = await this.prisma.userChallenge.findMany({
      where: {
        userId,
        ...(q.status ? { status: q.status } : {}),
        ...(after ? { OR: [{ joinedAt: { lt: after.t } }, { joinedAt: after.t, id: { lt: after.id } }] } : {}),
      },
      orderBy: [{ joinedAt: 'desc' }, { id: 'desc' }],
      take: limit + 1,
    });
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    return { items: page.map(toChallengeView), nextCursor: rows.length > limit && last ? encodeCursor({ t: last.joinedAt.toISOString(), id: last.id }) : null };
  }

  async getChallenge(userId: string, id: string, now = new Date()): Promise<ChallengeView> {
    const own = await this.prisma.userChallenge.findFirst({ where: { id, userId }, select: { id: true } });
    if (!own) throw notFound('Challenge');
    await this.heal(userId, now);
    return toChallengeView(await this.prisma.userChallenge.findUniqueOrThrow({ where: { id } }));
  }

  async join(userId: string, dto: ChallengeJoinDto, now = new Date()): Promise<ChallengeView> {
    const tpl = templateByCode(dto.templateCode);
    if (!tpl) throw invalid({ templateCode: 'unknown challenge template' });
    const i = tpl.targets.indexOf(dto.target);
    if (i < 0) throw invalid({ target: `must be one of ${tpl.targets.join(', ')}` });
    // Expire finished challenges first, so last week's instance doesn't block (or count towards the limit).
    await this.heal(userId, now);

    const profile = await this.prisma.userProfile.findUnique({ where: { userId }, select: { timezone: true, dailyWaterTargetMl: true } });
    const tz = profile?.timezone ?? DEFAULT_TIMEZONE;
    const today = localDate(now, tz);
    const windowStart = tpl.type === 'weekly' ? weekStart(today) : today;
    const deadline = tpl.deadlineDays?.[i] ?? null;
    const windowEnd = tpl.type === 'weekly' ? addDays(windowStart, 6) : deadline ? addDays(today, deadline - 1) : null;
    const waterGoalMl = tpl.metric === 'water_goal_days' ? profile?.dailyWaterTargetMl ?? DEFAULT_WATER_GOAL_ML : null;

    let id: string;
    try {
      id = await this.prisma.$transaction(async (tx) => {
        await this.engine.lock(tx, userId);
        const active = await tx.userChallenge.findMany({ where: { userId, status: 'active' }, select: { templateCode: true } });
        if (active.some((a) => a.templateCode === tpl.code)) throw alreadyJoined();
        if (tpl.type === 'weekly' && (await tx.userChallenge.count({ where: { userId, templateCode: tpl.code, windowStart: new Date(`${windowStart}T00:00:00Z`) } }))) throw alreadyJoined();
        if (active.length >= MAX_ACTIVE_CHALLENGES) {
          throw new ApiError(HttpStatus.CONFLICT, 'CHALLENGE_LIMIT_REACHED', `You can have at most ${MAX_ACTIVE_CHALLENGES} active challenges.`);
        }
        const row = await tx.userChallenge.create({
          data: {
            userId, templateCode: tpl.code, type: tpl.type, target: dto.target, timezone: tz, waterGoalMl, joinedAt: now,
            windowStart: new Date(`${windowStart}T00:00:00Z`), windowEnd: windowEnd ? new Date(`${windowEnd}T00:00:00Z`) : null, joinDate: new Date(`${today}T00:00:00Z`),
          },
          select: { id: true },
        });
        return row.id;
      });
    } catch (e) {
      // Belt and braces: the unique indexes also reject a duplicate.
      if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') throw alreadyJoined();
      throw e;
    }
    // Initial progress: a weekly challenge counts this week's activity, so it may complete right away.
    await this.heal(userId, now);
    return toChallengeView(await this.prisma.userChallenge.findUniqueOrThrow({ where: { id } }));
  }

  async leave(userId: string, id: string, now = new Date()): Promise<ChallengeView> {
    // The 409 is thrown after commit: throwing inside would roll back a completion settled here.
    const r = await this.prisma.$transaction(async (tx) => {
      await this.engine.lock(tx, userId);
      const own = await tx.userChallenge.findFirst({ where: { id, userId }, select: { id: true } });
      if (!own) throw notFound('Challenge');
      // Under the same per-user lock: a target reached just now completes (or a past window
      // expires) before we decide, so leaving can't race a completion.
      await this.engine.settleChallenges(tx, userId, now);
      const c = (await tx.userChallenge.findUnique({ where: { id } }))!;
      if (c.status === 'left') return toChallengeView(c);
      if (c.status !== 'active') return null;
      return toChallengeView(await tx.userChallenge.update({ where: { id }, data: { status: 'left', leftAt: now } }));
    });
    if (!r) throw new ApiError(HttpStatus.CONFLICT, 'CHALLENGE_NOT_ACTIVE', 'This challenge has already ended.');
    return r;
  }
}
