/**
 * Suggested macro split v1 (docs/specs/macro-split-suggestion.md). Whole-number maths only:
 * float expressions like 0.35 * 2700 / 9 or 87.5 * 1.4 floor/round to the wrong gram.
 */
export type SuggestionGoal = 'lose_weight' | 'gain_muscle' | 'maintain' | 'improve_endurance';
export type MissingInput = 'CALORIE_TARGET' | 'WEIGHT';

export const FORMULA_VERSION = 1;
export const FAT_PCT = 25;
export const CARBS_MAX_G = 1000;
/** Protein g/kg × 10. */
export const PROTEIN_PER_KG_X10: Record<SuggestionGoal, number> = {
  lose_weight: 16,
  gain_muscle: 18,
  improve_endurance: 14,
  maintain: 12,
};

export interface MacroSplit { proteinG: number; carbsG: number; fatG: number; kcal: number }

/** Floor division for non-negative integers (exact; operands stay well below 2^53). */
const fdiv = (a: number, b: number): number => Math.floor(a / b);

/** Exact hundredths of a kg from a decimal string/number with at most 2 decimals (e.g. "87.50"). */
export function hundredths(value: string): number {
  const m = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!m) throw new Error(`bad weight ${value}`);
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

/** Weight rounded half-up to tenths of a kg, as an integer. */
export const tenthsFromHundredths = (h: number): number => fdiv(h + 5, 10);

/** K = calorie target (integer kcal), w = weight in tenths of a kg (integer). */
export function macroSplit(K: number, w: number, goal: SuggestionGoal): MacroSplit {
  const pk = PROTEIN_PER_KG_X10[goal];
  const P = Math.min(fdiv(w * pk + 50, 100), fdiv(3 * K, 40));
  const F = Math.min(Math.max(fdiv(K + 18, 36), fdiv(6 * w + 50, 100)), fdiv(7 * K, 180));
  const C = Math.min(fdiv(K - 4 * P - 9 * F, 4), CARBS_MAX_G);
  return { proteinG: P, carbsG: C, fatG: F, kcal: 4 * P + 4 * C + 9 * F };
}

export function missingInputs(K: number | null, weight: unknown): MissingInput[] {
  const out: MissingInput[] = [];
  if (K === null) out.push('CALORIE_TARGET');
  if (weight === null || weight === undefined) out.push('WEIGHT');
  return out;
}
