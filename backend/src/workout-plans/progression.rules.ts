/**
 * Pure rules for adaptive progression (docs/specs/adaptive-progression.md). No I/O, no AI.
 */
export type TrackingType = 'reps_weight' | 'reps' | 'duration' | 'distance_duration';
export type Level = 'beginner' | 'intermediate' | 'advanced';
export type Action = 'increase' | 'hold' | 'deload';
export type Reason =
  | 'ALL_SETS_COMPLETED' | 'NO_TARGET_SET' | 'NOT_ENOUGH_HISTORY' | 'MISSED_TARGET' | 'HIGH_EFFORT'
  | 'COOLDOWN' | 'CAP_REACHED' | 'REP_CEILING' | 'REPEATED_MISSES' | 'VERY_HARD';

export interface Prescription {
  sets: number;
  repsMin: number | null;
  repsMax: number | null;
  targetWeightKg: number | null;
  durationSeconds: number | null;
}
export interface SetRow { reps: number | null; weightKg: number | null; durationSeconds: number | null; distanceM: number | null }

export const WINDOW = 3;
export const COOLDOWN_MS = 7 * 86_400_000;
export const LOWER_BODY = new Set(['quads', 'hamstrings', 'glutes', 'calves']);
export const LIMITS = { weight: 1000, reps: 100, duration: 7200, repCeiling: 20 };
const LEVELS: Level[] = ['beginner', 'intermediate', 'advanced'];

export const REASON_TEXT: Record<Reason, string> = {
  ALL_SETS_COMPLETED: 'You completed every prescribed set in your last 3 sessions.',
  NO_TARGET_SET: 'No target weight yet: suggesting the heaviest weight you used in your last 3 sessions.',
  NOT_ENOUGH_HISTORY: 'Complete this exercise in 3 sessions to get a suggestion.',
  MISSED_TARGET: 'A recent session missed some of the prescribed sets, reps, weight or time.',
  HIGH_EFFORT: 'A recent session felt hard, so keep the current load for now.',
  COOLDOWN: 'This exercise was progressed less than 7 days ago.',
  CAP_REACHED: 'This exercise is already at the maximum the plan allows.',
  REP_CEILING: 'You have reached 20 reps: consider a harder variation.',
  REPEATED_MISSES: 'Your last two sessions missed the target, so a lighter load is suggested.',
  VERY_HARD: 'Your last two sessions felt very hard, so a lighter load is suggested.',
};

/** Rounds down to a multiple of 0.5 (kg). */
export const floorHalf = (v: number) => Math.floor(v * 2 + 1e-9) / 2;
/** Rounds down to a multiple of 5 (seconds). */
export const floor5 = (v: number) => Math.floor(v / 5 + 1e-9) * 5;

/** Rule 2: did the session's counted sets meet the prescription? */
export function metPrescription(t: TrackingType, p: Prescription, sets: SetRow[]): boolean {
  if (sets.length < p.sets) return false;
  return sets.every((s) => {
    if (t === 'reps_weight' || t === 'reps') {
      const reps = p.repsMax ?? p.repsMin;
      if (reps != null && (s.reps ?? 0) < reps) return false;
      if (t === 'reps_weight' && p.targetWeightKg != null && (s.weightKg ?? 0) < p.targetWeightKg) return false;
      return true;
    }
    return p.durationSeconds == null || (s.durationSeconds ?? 0) >= p.durationSeconds;
  });
}

export type Step = { action: Action; reason: Reason; suggested: Prescription | null; harder?: true };

/** One increase step (trigger already met). Min step overrides the 10% load cap; rep steps are exempt. */
export function increase(t: TrackingType, p: Prescription, primaryMuscle: string, heaviestKg: number | null): Step {
  const hold = (reason: Reason): Step => ({ action: 'hold', reason, suggested: null });
  if (t === 'reps_weight') {
    if (p.targetWeightKg == null) {
      if (heaviestKg == null || heaviestKg <= 0) return hold('NOT_ENOUGH_HISTORY');
      return { action: 'increase', reason: 'NO_TARGET_SET', suggested: { ...p, targetWeightKg: Math.min(floorHalf(heaviestKg), LIMITS.weight) } };
    }
    const w = p.targetWeightKg;
    if (w >= LIMITS.weight) return hold('CAP_REACHED');
    const pct = LOWER_BODY.has(primaryMuscle) ? 0.05 : 0.025;
    const minStep = w < 10 ? 0.5 : 1;
    const step = Math.min(Math.max(w * pct, minStep), Math.max(w * 0.1, minStep)); // cap 10%, min step wins
    let next = floorHalf(w + step);
    if (next <= w) next = floorHalf(w) + 0.5;
    return { action: 'increase', reason: 'ALL_SETS_COMPLETED', suggested: { ...p, targetWeightKg: Math.min(next, LIMITS.weight) } };
  }
  if (t === 'reps') {
    const top = p.repsMax ?? p.repsMin;
    if (top == null) return hold('NOT_ENOUGH_HISTORY');
    const step = top >= 15 ? 2 : 1;
    if (top + step > LIMITS.repCeiling) return { action: 'hold', reason: 'REP_CEILING', suggested: null, harder: true };
    return {
      action: 'increase', reason: 'ALL_SETS_COMPLETED',
      suggested: { ...p, repsMin: p.repsMin == null ? null : p.repsMin + step, repsMax: p.repsMax == null ? null : p.repsMax + step },
    };
  }
  const d = p.durationSeconds;
  if (d == null) return hold('NOT_ENOUGH_HISTORY');
  if (d >= LIMITS.duration) return hold('CAP_REACHED');
  const step = Math.max(floor5(d * 0.1), 5);
  return { action: 'increase', reason: 'ALL_SETS_COMPLETED', suggested: { ...p, durationSeconds: Math.min(d + step, LIMITS.duration) } };
}

/** −10% load: weight down to 0.5 kg, duration down to 5 s. Null when there is no load to lower. */
export function deload(t: TrackingType, p: Prescription): Prescription | null {
  if (t === 'reps_weight' && p.targetWeightKg != null && p.targetWeightKg > 0) {
    const next = floorHalf(p.targetWeightKg * 0.9);
    return next < p.targetWeightKg ? { ...p, targetWeightKg: next } : null;
  }
  if ((t === 'duration' || t === 'distance_duration') && p.durationSeconds != null) {
    const next = Math.max(floor5(p.durationSeconds * 0.9), Math.min(5, p.durationSeconds));
    return next < p.durationSeconds ? { ...p, durationSeconds: next } : null;
  }
  return null;
}

export const nextLevel = (l: Level): Level | null => LEVELS[LEVELS.indexOf(l) + 1] ?? null;

/** Harder variations by rule (no curated links): same muscle and tracking, one level up, compatible equipment; name, then id. */
export function harderVariations(
  ex: { id: string; primaryMuscle: string; trackingType: string; difficulty: Level; equipment: string[] },
  catalog: { id: string; name: string; primaryMuscle: string; trackingType: string; difficulty: string; equipment: string[] }[],
): string[] {
  const level = nextLevel(ex.difficulty);
  if (!level) return [];
  return catalog
    .filter((c) => c.id !== ex.id && c.primaryMuscle === ex.primaryMuscle && c.trackingType === ex.trackingType && c.difficulty === level)
    .filter((c) => c.equipment.every((e) => e === 'none' || ex.equipment.includes(e)))
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
    .slice(0, 3)
    .map((c) => c.id);
}

/** Whether `cur` raises the prescribed load (weight, reps or duration) over `prev`. */
export function raisedLoad(prev: Prescription | undefined, cur: Prescription | undefined): boolean {
  if (!prev || !cur) return false;
  const up = (a: number | null, b: number | null) => a != null && b != null && b > a;
  return up(prev.targetWeightKg, cur.targetWeightKg) || up(prev.repsMax, cur.repsMax) || up(prev.repsMin, cur.repsMin) || up(prev.durationSeconds, cur.durationSeconds);
}
