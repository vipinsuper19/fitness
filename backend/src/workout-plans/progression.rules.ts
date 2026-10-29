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
/** Rounds up to a multiple of 0.5 (kg). */
export const ceilHalf = (v: number) => Math.ceil(v * 2 - 1e-9) / 2;
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

/** How `suggested` was derived from `current` (ProgressionInputs.step in openapi). */
export interface StepWorking {
  field: 'targetWeightKg' | 'reps' | 'durationSeconds';
  rule: 'percent_increase' | 'heaviest_used' | 'rep_step' | 'deload';
  /** Rule percentage (2.5, 5, 10, -10); null for rep_step and heaviest_used. */
  percent: number | null;
  /** 10 for load increases; null when exempt (reps, deload, heaviest_used). */
  capPercent: number | null;
  capApplied: boolean;
  minStepApplied: boolean;
  /** The rounding rule used (reported even when the value already sat on the grid). */
  rounding: 'down_0_5_kg' | 'up_0_5_kg' | 'down_5_s' | 'none';
  limitApplied: boolean;
}

export type Step = { action: Action; reason: Reason; suggested: Prescription | null; harder?: true; working: StepWorking | null };

const EPS = 1e-9;

/** One increase step (trigger already met). Min step overrides the 10% load cap; rep steps are exempt. */
export function increase(t: TrackingType, p: Prescription, primaryMuscle: string, heaviestKg: number | null): Step {
  const hold = (reason: Reason): Step => ({ action: 'hold', reason, suggested: null, working: null });
  if (t === 'reps_weight') {
    if (p.targetWeightKg == null) {
      if (heaviestKg == null || heaviestKg <= 0) return hold('NOT_ENOUGH_HISTORY');
      const floored = floorHalf(heaviestKg);
      return {
        action: 'increase', reason: 'NO_TARGET_SET', suggested: { ...p, targetWeightKg: Math.min(floored, LIMITS.weight) },
        working: { field: 'targetWeightKg', rule: 'heaviest_used', percent: null, capPercent: null, capApplied: false, minStepApplied: false, rounding: 'down_0_5_kg', limitApplied: floored > LIMITS.weight },
      };
    }
    const w = p.targetWeightKg;
    if (w >= LIMITS.weight) return hold('CAP_REACHED');
    const pct = LOWER_BODY.has(primaryMuscle) ? 0.05 : 0.025;
    const minStep = w < 10 ? 0.5 : 1;
    const raw = w * pct;
    const cap = w * 0.1;
    const step = Math.min(Math.max(raw, minStep), Math.max(cap, minStep)); // cap 10%, min step wins
    // Round down to 0.5 kg, but never below current + min step (targets off the 0.5 grid, e.g. 40.3 -> 41.5).
    let next = floorHalf(w + step);
    let rounding: StepWorking['rounding'] = 'down_0_5_kg';
    let minStepApplied = raw < minStep - EPS;
    if (next < w + minStep - EPS) {
      next = ceilHalf(w + minStep);
      rounding = 'up_0_5_kg';
      minStepApplied = true;
    }
    return {
      action: 'increase', reason: 'ALL_SETS_COMPLETED', suggested: { ...p, targetWeightKg: Math.min(next, LIMITS.weight) },
      working: {
        field: 'targetWeightKg', rule: 'percent_increase', percent: pct * 100, capPercent: 10,
        capApplied: Math.max(raw, minStep) > Math.max(cap, minStep) + EPS, minStepApplied, rounding, limitApplied: next > LIMITS.weight,
      },
    };
  }
  if (t === 'reps') {
    const top = p.repsMax ?? p.repsMin;
    if (top == null) return hold('NOT_ENOUGH_HISTORY');
    const step = top >= 15 ? 2 : 1;
    if (top + step > LIMITS.repCeiling) return { action: 'hold', reason: 'REP_CEILING', suggested: null, harder: true, working: null };
    return {
      action: 'increase', reason: 'ALL_SETS_COMPLETED',
      suggested: { ...p, repsMin: p.repsMin == null ? null : p.repsMin + step, repsMax: p.repsMax == null ? null : p.repsMax + step },
      working: { field: 'reps', rule: 'rep_step', percent: null, capPercent: null, capApplied: false, minStepApplied: false, rounding: 'none', limitApplied: false },
    };
  }
  const d = p.durationSeconds;
  if (d == null) return hold('NOT_ENOUGH_HISTORY');
  if (d >= LIMITS.duration) return hold('CAP_REACHED');
  const pctStep = floor5(d * 0.1);
  const step = Math.max(pctStep, 5);
  return {
    action: 'increase', reason: 'ALL_SETS_COMPLETED', suggested: { ...p, durationSeconds: Math.min(d + step, LIMITS.duration) },
    working: {
      field: 'durationSeconds', rule: 'percent_increase', percent: 10, capPercent: 10, capApplied: false,
      minStepApplied: pctStep < 5, rounding: 'down_5_s', limitApplied: d + step > LIMITS.duration,
    },
  };
}

/** −10% load with its working: weight down to 0.5 kg, duration down to 5 s. Null when there is no load to lower. */
export function deloadStep(t: TrackingType, p: Prescription): { suggested: Prescription; working: StepWorking } | null {
  const base = { rule: 'deload' as const, percent: -10, capPercent: null, capApplied: false, minStepApplied: false };
  if (t === 'reps_weight' && p.targetWeightKg != null && p.targetWeightKg > 0) {
    const next = floorHalf(p.targetWeightKg * 0.9);
    return next < p.targetWeightKg
      ? { suggested: { ...p, targetWeightKg: next }, working: { ...base, field: 'targetWeightKg', rounding: 'down_0_5_kg', limitApplied: false } }
      : null;
  }
  if ((t === 'duration' || t === 'distance_duration') && p.durationSeconds != null) {
    // The 5 s floor only matters at <= 5 s, where nothing is lowered (null), so a deload never reports a limit.
    const next = Math.max(floor5(p.durationSeconds * 0.9), Math.min(5, p.durationSeconds));
    return next < p.durationSeconds
      ? { suggested: { ...p, durationSeconds: next }, working: { ...base, field: 'durationSeconds', rounding: 'down_5_s', limitApplied: false } }
      : null;
  }
  return null;
}

/** −10% load: weight down to 0.5 kg, duration down to 5 s. Null when there is no load to lower. */
export function deload(t: TrackingType, p: Prescription): Prescription | null {
  return deloadStep(t, p)?.suggested ?? null;
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
