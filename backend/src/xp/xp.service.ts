import { Injectable } from '@nestjs/common';

import { decodeTimeCursor, encodeCursor } from '../common/cursor';
import type { Prisma, XPTransaction } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { balanceFor, XpBalance } from './xp.rules';

type Db = PrismaService | Prisma.TransactionClient;

export interface XpEntryView {
  id: string;
  sourceType: 'workout_session' | 'adjustment';
  sourceId: string;
  amount: number;
  reason: string;
  createdAt: string;
}

export const toXpView = (x: XPTransaction): XpEntryView => ({
  id: x.id,
  sourceType: x.sourceType,
  sourceId: x.sourceId,
  amount: x.amount,
  reason: x.reason,
  createdAt: x.createdAt.toISOString(),
});

/**
 * The ledger is append-only and unique per (userId, sourceType, sourceId):
 * - an award is keyed by the session id, so a session awards at most once;
 * - a reversal is an `adjustment` keyed by the award's id, so an award is
 *   reversed at most once, and because the award row stays, the session
 *   can never be awarded again.
 */
@Injectable()
export class XpService {
  constructor(private readonly prisma: PrismaService) {}

  async balance(userId: string, db: Db = this.prisma): Promise<XpBalance> {
    const sum = await db.xPTransaction.aggregate({ where: { userId }, _sum: { amount: true } });
    return balanceFor(sum._sum.amount ?? 0);
  }

  async ledger(userId: string, cursor?: string, limit = 20): Promise<{ items: XpEntryView[]; nextCursor: string | null }> {
    const after = decodeTimeCursor(cursor);
    const rows = await this.prisma.xPTransaction.findMany({
      where: {
        userId,
        ...(after ? { OR: [{ createdAt: { lt: after.t } }, { createdAt: after.t, id: { lt: after.id } }] } : {}),
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: limit + 1,
    });
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    return {
      items: page.map(toXpView),
      nextCursor: rows.length > limit && last ? encodeCursor({ t: last.createdAt.toISOString(), id: last.id }) : null,
    };
  }

  /** The session's award, if any (still present after a reversal). */
  findAward(db: Db, userId: string, sessionId: string): Promise<XPTransaction | null> {
    return db.xPTransaction.findUnique({
      where: { userId_sourceType_sourceId: { userId, sourceType: 'workout_session', sourceId: sessionId } },
    });
  }

  /** Awards once; returns the existing award if there already is one. */
  async awardWorkout(db: Db, userId: string, sessionId: string, amount: number): Promise<XPTransaction | null> {
    const existing = await this.findAward(db, userId, sessionId);
    if (existing || amount <= 0) return existing;
    return db.xPTransaction.create({
      data: { userId, sourceType: 'workout_session', sourceId: sessionId, amount, reason: 'Workout completed' },
    });
  }

  /** Reverses an award once (no-op if already reversed). */
  async reverse(db: Db, award: XPTransaction, reason: string): Promise<boolean> {
    const done = await db.xPTransaction.findUnique({
      where: { userId_sourceType_sourceId: { userId: award.userId, sourceType: 'adjustment', sourceId: award.id } },
    });
    if (done) return false;
    await db.xPTransaction.create({
      data: { userId: award.userId, sourceType: 'adjustment', sourceId: award.id, amount: -award.amount, reason },
    });
    return true;
  }
}
