/**
 * XP rules (server only; there is no client endpoint that writes XP).
 *
 * Workout completion: 20 base + 2 per completed set (up to 30 sets) + 1 per
 * 5 active minutes (up to 20), capped at 100 per session. Paused time does
 * not count. A session with no completed sets cannot be completed.
 *
 * Levels: reaching level n+1 from level n takes 100 * n XP, so levels 2, 3,
 * 4 start at 100, 300 and 600 total XP.
 */
export const XP_BASE = 20;
export const XP_PER_SET = 2;
export const XP_MAX_SETS = 30;
export const XP_PER_5_MIN = 1;
export const XP_MAX_TIME = 20;
export const XP_SESSION_CAP = 100;

export function workoutXp(completedSets: number, activeSeconds: number): number {
  if (completedSets <= 0) return 0;
  const sets = Math.min(completedSets, XP_MAX_SETS) * XP_PER_SET;
  const time = Math.min(Math.floor(activeSeconds / 300) * XP_PER_5_MIN, XP_MAX_TIME);
  return Math.min(XP_BASE + sets + time, XP_SESSION_CAP);
}

export interface XpBalance {
  total: number;
  level: number;
  xpIntoLevel: number;
  xpForNextLevel: number;
}

export function balanceFor(rawTotal: number): XpBalance {
  const total = Math.max(0, rawTotal);
  let level = 1;
  let floor = 0;
  while (total >= floor + 100 * level) {
    floor += 100 * level;
    level += 1;
  }
  return { total, level, xpIntoLevel: total - floor, xpForNextLevel: 100 * level };
}
