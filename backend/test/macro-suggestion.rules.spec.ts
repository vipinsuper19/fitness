import { hundredths, macroSplit, missingInputs, tenthsFromHundredths, type SuggestionGoal } from '../src/me/macro-suggestion.rules';

const kg = (s: string) => tenthsFromHundredths(hundredths(s));

describe('macro suggestion rules v1 (pure, whole-number maths)', () => {
  const table: Array<[string, number, string, SuggestionGoal, number, number, number, number]> = [
    ['A', 2000, '70', 'lose_weight', 112, 56, 262, 2000],
    ['B', 1500, '90', 'gain_muscle', 112, 54, 141, 1498],
    ['C', 2400, '60', 'maintain', 72, 67, 377, 2399],
    ['D', 2800, '75', 'improve_endurance', 105, 78, 419, 2798],
    ['E', 1200, '80', 'maintain', 90, 46, 106, 1198],
    ['F', 6000, '20', 'maintain', 24, 167, 1000, 5599],
    ['G', 2700, '180', 'maintain', 202, 105, 236, 2697],
    ['H', 2500, '87.5', 'improve_endurance', 123, 69, 346, 2497],
  ];
  it.each(table)('example %s: K=%d W=%s %s', (_n, K, W, goal, P, F, C, kcal) => {
    const s = macroSplit(K, kg(W), goal);
    expect(s).toEqual({ proteinG: P, fatG: F, carbsG: C, kcal });
    expect(s.kcal).toBe(4 * s.proteinG + 4 * s.carbsG + 9 * s.fatG);
  });

  it('K = 2700: fat cap is floor(7K/180) = 105, not the float 104', () => {
    expect(Math.floor((0.35 * 2700) / 9)).toBe(104); // the float trap
    expect(macroSplit(2700, kg('180'), 'maintain').fatG).toBe(105);
  });

  it('87.5 kg endurance: protein rounds half-up to 123, not the float 122', () => {
    expect(Math.round(87.5 * 1.4)).toBe(122); // the float trap
    expect(macroSplit(2500, kg('87.5'), 'improve_endurance').proteinG).toBe(123);
  });

  it('fat cap is exact at every float-trap K', () => {
    for (const K of [1440, 2700, 2880, 5220, 5400, 5580, 5760]) {
      expect(macroSplit(K, kg('400'), 'maintain').fatG).toBe([56, 105, 112, 203, 210, 217, 224][[1440, 2700, 2880, 5220, 5400, 5580, 5760].indexOf(K)]);
    }
  });

  it('kcal is the rounded sum, never above K; at most 3 under unless carbs hit 1000 g', () => {
    for (let K = 800; K <= 6000; K += 7) {
      for (const W of ['20', '45.55', '87.5', '150', '400']) {
        for (const goal of ['lose_weight', 'gain_muscle', 'maintain', 'improve_endurance'] as const) {
          const s = macroSplit(K, kg(W), goal);
          for (const v of [s.proteinG, s.carbsG, s.fatG]) {
            expect(Number.isInteger(v) && v >= 0 && v <= 1000).toBe(true);
          }
          expect(s.kcal).toBe(4 * s.proteinG + 4 * s.carbsG + 9 * s.fatG);
          expect(s.kcal).toBeLessThanOrEqual(K);
          if (s.carbsG < 1000) expect(K - s.kcal).toBeLessThanOrEqual(3);
        }
      }
    }
  });

  it('weight is taken in tenths, rounded half-up from the stored 2 decimals', () => {
    expect([kg('87.5'), kg('87.50'), kg('87.45'), kg('87.44'), kg('70')]).toEqual([875, 875, 875, 874, 700]);
  });

  it('missingInputs lists every missing input in order, empty when both are set', () => {
    expect(missingInputs(null, 70)).toEqual(['CALORIE_TARGET']);
    expect(missingInputs(2000, null)).toEqual(['WEIGHT']);
    expect(missingInputs(null, null)).toEqual(['CALORIE_TARGET', 'WEIGHT']);
    expect(missingInputs(2000, 70)).toEqual([]);
  });
});
