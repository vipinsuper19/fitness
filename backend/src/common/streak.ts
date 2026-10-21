import { nextDate, prevDate } from './time';

/**
 * Consecutive local days in `done` ending today. If today has none yet, the
 * run ending yesterday is still alive and is returned (progress summary rule).
 */
export function currentStreak(done: ReadonlySet<string>, today: string): number {
  let day = done.has(today) ? today : prevDate(today);
  let count = 0;
  while (done.has(day)) {
    count += 1;
    day = prevDate(day);
  }
  return count;
}

/** Longest run of consecutive dates in `days`, counting only dates within [from, to] (both optional, inclusive). */
export function longestRun(days: Iterable<string>, from?: string, to?: string): number {
  const sorted = [...new Set(days)].filter((d) => (!from || d >= from) && (!to || d <= to)).sort();
  let best = 0;
  let run = 0;
  let prev: string | null = null;
  for (const d of sorted) {
    run = prev !== null && nextDate(prev) === d ? run + 1 : 1;
    best = Math.max(best, run);
    prev = d;
  }
  return best;
}
