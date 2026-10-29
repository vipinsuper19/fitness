/**
 * Dated daily-target history (docs/specs/target-history.md). The targets in effect on a local
 * date are the newest row with effective_from <= that date; with none, all five are null.
 */
import { isoDate, parseIsoDate } from '../common/decimal';
import { localDate } from '../common/time';
import type { Prisma, PrismaClient, ProfileTargetHistory } from '../generated/prisma/client';

export const BACKFILL_DATE = '1900-01-01';
export const MAX_HISTORY_ITEMS = 1000;

export interface TargetSnapshot { kcal: number | null; waterMl: number | null; proteinG: number | null; carbsG: number | null; fatG: number | null }
export interface TargetHistoryEntry extends TargetSnapshot { effectiveFrom: string }

type TargetCols = {
  dailyCalorieTargetKcal: number | null; dailyWaterTargetMl: number | null;
  dailyProteinTargetG: number | null; dailyCarbsTargetG: number | null; dailyFatTargetG: number | null;
};
type Db = Prisma.TransactionClient | PrismaClient;

/** 0 (or negative) counts as no target. */
const t = (v: number | null | undefined): number | null => (v == null || v <= 0 ? null : v);

export const NO_TARGETS: TargetSnapshot = { kcal: null, waterMl: null, proteinG: null, carbsG: null, fatG: null };

export function snapshotOf(p: TargetCols | null | undefined): TargetSnapshot {
  if (!p) return NO_TARGETS;
  return { kcal: t(p.dailyCalorieTargetKcal), waterMl: t(p.dailyWaterTargetMl), proteinG: t(p.dailyProteinTargetG), carbsG: t(p.dailyCarbsTargetG), fatG: t(p.dailyFatTargetG) };
}

export const sameTargets = (a: TargetSnapshot, b: TargetSnapshot): boolean =>
  a.kcal === b.kcal && a.waterMl === b.waterMl && a.proteinG === b.proteinG && a.carbsG === b.carbsG && a.fatG === b.fatG;

export const isEmpty = (s: TargetSnapshot): boolean => sameTargets(s, NO_TARGETS);

const cols = (s: TargetSnapshot) => ({
  dailyCalorieTargetKcal: s.kcal, dailyWaterTargetMl: s.waterMl, dailyProteinTargetG: s.proteinG, dailyCarbsTargetG: s.carbsG, dailyFatTargetG: s.fatG,
});

export const toEntry = (r: ProfileTargetHistory): TargetHistoryEntry => ({ effectiveFrom: isoDate(r.effectiveFrom)!, ...snapshotOf(r) });

/** Writes or replaces the row for `date` with the full snapshot. */
export async function upsertRow(db: Db, userId: string, date: string, s: TargetSnapshot): Promise<void> {
  const effectiveFrom = parseIsoDate(date)!;
  await db.profileTargetHistory.upsert({
    where: { userId_effectiveFrom: { userId, effectiveFrom } },
    create: { userId, effectiveFrom, ...cols(s) },
    update: cols(s),
  });
}

/** Called inside the PUT /me/profile transaction: today's row (in `tz`, the zone after the PUT) when any target changed. */
export async function recordTargetChange(db: Db, userId: string, before: TargetSnapshot, after: TargetSnapshot, tz: string, now: Date): Promise<boolean> {
  if (sameTargets(before, after)) return false;
  await upsertRow(db, userId, localDate(now, tz), after);
  return true;
}

/** Newest first. */
export async function listHistory(db: Db, userId: string, take = MAX_HISTORY_ITEMS): Promise<TargetHistoryEntry[]> {
  const rows = await db.profileTargetHistory.findMany({ where: { userId }, orderBy: { effectiveFrom: 'desc' }, take });
  return rows.map(toEntry);
}

/**
 * Resolver for dates in ascending order (or any order; it binary-searches). `rows` are the
 * user's history rows with effective_from <= the last date needed.
 */
export function targetsResolver(entries: TargetHistoryEntry[]): (date: string) => TargetSnapshot {
  const asc = [...entries].sort((a, b) => (a.effectiveFrom < b.effectiveFrom ? -1 : a.effectiveFrom > b.effectiveFrom ? 1 : 0));
  return (date) => {
    let lo = 0;
    let hi = asc.length - 1;
    let hit: TargetHistoryEntry | null = null;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (asc[mid]!.effectiveFrom <= date) { hit = asc[mid]!; lo = mid + 1; } else hi = mid - 1;
    }
    return hit ? { kcal: hit.kcal, waterMl: hit.waterMl, proteinG: hit.proteinG, carbsG: hit.carbsG, fatG: hit.fatG } : NO_TARGETS;
  };
}

/** Loads the rows needed to resolve dates up to `to` and returns a resolver. */
export async function loadResolver(db: Db, userId: string, to: string): Promise<(date: string) => TargetSnapshot> {
  const rows = await db.profileTargetHistory.findMany({ where: { userId, effectiveFrom: { lte: parseIsoDate(to)! } }, orderBy: { effectiveFrom: 'asc' } });
  return targetsResolver(rows.map(toEntry));
}

/**
 * Reconcile (npm run targets:backfill), idempotent: for each profile whose newest history row
 * differs from its current targets, upsert a row: 1900-01-01 when it has none, else today in
 * the profile timezone. Profiles with no targets and no rows are skipped.
 */
export async function reconcileAll(db: PrismaClient, now: Date, log: (m: string) => void = () => undefined): Promise<{ profiles: number; written: number; failed: number }> {
  const total = { profiles: 0, written: 0, failed: 0 };
  let cursor: string | undefined;
  for (;;) {
    const profiles = await db.userProfile.findMany({
      select: { userId: true, timezone: true, dailyCalorieTargetKcal: true, dailyWaterTargetMl: true, dailyProteinTargetG: true, dailyCarbsTargetG: true, dailyFatTargetG: true },
      orderBy: { userId: 'asc' }, take: 200, ...(cursor ? { cursor: { userId: cursor }, skip: 1 } : {}),
    });
    if (!profiles.length) break;
    for (const p of profiles) {
      total.profiles += 1;
      try {
        if (await reconcileOne(db, p.userId, snapshotOf(p), p.timezone, now)) total.written += 1;
      } catch (e) {
        total.failed += 1;
        log(`user ${p.userId}: ${e instanceof Error ? e.message : String(e)}`);
      }
    }
    cursor = profiles[profiles.length - 1]!.userId;
  }
  return total;
}

export async function reconcileOne(db: Db, userId: string, current: TargetSnapshot, tz: string, now: Date): Promise<boolean> {
  const newest = await db.profileTargetHistory.findFirst({ where: { userId }, orderBy: { effectiveFrom: 'desc' } });
  if (!newest) {
    if (isEmpty(current)) return false;
    await upsertRow(db, userId, BACKFILL_DATE, current);
    return true;
  }
  if (sameTargets(snapshotOf(newest), current)) return false;
  await upsertRow(db, userId, localDate(now, tz), current);
  return true;
}
