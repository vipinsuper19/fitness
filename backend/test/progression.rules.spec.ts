import { deload, floor5, floorHalf, harderVariations, increase, metPrescription, raisedLoad, REASON_TEXT, type Prescription } from '../src/workout-plans/progression.rules';

const p = (o: Partial<Prescription>): Prescription => ({ sets: 3, repsMin: null, repsMax: null, targetWeightKg: null, durationSeconds: null, ...o });

describe('progression rules (pure)', () => {
  it('rounds weights down to 0.5 kg and durations down to 5 s', () => {
    expect([floorHalf(41.4), floorHalf(41.5), floorHalf(8.2)]).toEqual([41, 41.5, 8]);
    expect([floor5(58.5), floor5(30), floor5(4)]).toEqual([55, 30, 0]);
  });

  it('weight increments: +2.5% upper, +5% lower, floors 1.0 / 0.5 kg, 0.5 kg multiples', () => {
    expect(increase('reps_weight', p({ targetWeightKg: 40 }), 'chest', null).suggested!.targetWeightKg).toBe(41);
    expect(increase('reps_weight', p({ targetWeightKg: 100 }), 'quads', null).suggested!.targetWeightKg).toBe(105);
    expect(increase('reps_weight', p({ targetWeightKg: 100 }), 'back', null).suggested!.targetWeightKg).toBe(102.5);
    expect(increase('reps_weight', p({ targetWeightKg: 8 }), 'biceps', null).suggested!.targetWeightKg).toBe(8.5);
    expect(increase('reps_weight', p({ targetWeightKg: 61 }), 'glutes', null).suggested!.targetWeightKg).toBe(64); // 64.05 -> 64
  });

  it('min load step overrides the 10% cap; rep steps are exempt', () => {
    expect(increase('reps_weight', p({ targetWeightKg: 4 }), 'chest', null).suggested!.targetWeightKg).toBe(4.5); // +12.5%
    expect(increase('duration', p({ durationSeconds: 30 }), 'core', null).suggested!.durationSeconds).toBe(35); // +16.7%
    expect(increase('reps', p({ repsMin: 3, repsMax: 5 }), 'core', null).suggested).toMatchObject({ repsMin: 4, repsMax: 6 }); // +20%
  });

  it('reps: +1, +2 from 15, REP_CEILING past 20', () => {
    expect(increase('reps', p({ repsMin: 8, repsMax: 12 }), 'chest', null).suggested).toMatchObject({ repsMin: 9, repsMax: 13 });
    expect(increase('reps', p({ repsMin: 15, repsMax: 18 }), 'chest', null).suggested).toMatchObject({ repsMin: 17, repsMax: 20 });
    expect(increase('reps', p({ repsMin: 19, repsMax: 20 }), 'chest', null)).toMatchObject({ action: 'hold', reason: 'REP_CEILING', harder: true });
  });

  it('duration: +10% down to 5 s, min 5 s; CAP_REACHED at limits', () => {
    expect(increase('duration', p({ durationSeconds: 60 }), 'core', null).suggested!.durationSeconds).toBe(65);
    expect(increase('distance_duration', p({ durationSeconds: 300 }), 'cardio', null).suggested!.durationSeconds).toBe(330);
    expect(increase('duration', p({ durationSeconds: 7200 }), 'core', null).reason).toBe('CAP_REACHED');
    expect(increase('reps_weight', p({ targetWeightKg: 1000 }), 'quads', null).reason).toBe('CAP_REACHED');
  });

  it('NO_TARGET_SET uses the heaviest weight rounded down to 0.5 kg', () => {
    expect(increase('reps_weight', p({}), 'chest', 37.3)).toMatchObject({ action: 'increase', reason: 'NO_TARGET_SET', suggested: { targetWeightKg: 37 } });
  });

  it('deload: -10%, weight down to 0.5 kg, duration down to 5 s; none for reps', () => {
    expect(deload('reps_weight', p({ targetWeightKg: 50 }))!.targetWeightKg).toBe(45);
    expect(deload('reps_weight', p({ targetWeightKg: 41 }))!.targetWeightKg).toBe(36.5);
    expect(deload('duration', p({ durationSeconds: 65 }))!.durationSeconds).toBe(55);
    expect(deload('reps', p({ repsMin: 8, repsMax: 12 }))).toBeNull();
  });

  it('metPrescription: sets, reps to repsMax (or repsMin), weight, duration', () => {
    const s = (reps: number, w: number | null = null) => ({ reps, weightKg: w, durationSeconds: null, distanceM: null });
    expect(metPrescription('reps_weight', p({ sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: 40 }), [s(10, 40), s(10, 42.5)])).toBe(true);
    expect(metPrescription('reps_weight', p({ sets: 2, repsMin: 8, repsMax: 10, targetWeightKg: 40 }), [s(10, 40), s(9, 40)])).toBe(false);
    expect(metPrescription('reps_weight', p({ sets: 2, repsMax: 10, targetWeightKg: 40 }), [s(10, 40), s(10, 37.5)])).toBe(false);
    expect(metPrescription('reps', p({ sets: 3, repsMin: 8 }), [s(8), s(8)])).toBe(false);
    expect(metPrescription('duration', p({ sets: 1, durationSeconds: 60 }), [{ reps: null, weightKg: null, durationSeconds: 60, distanceM: null }])).toBe(true);
  });

  it('harderVariations: rule-based, one level up, compatible equipment, name then id, max 3', () => {
    const ex = { id: 'x', primaryMuscle: 'core', trackingType: 'reps', difficulty: 'beginner' as const, equipment: ['none'] };
    const c = (id: string, name: string, o: object = {}) => ({ id, name, primaryMuscle: 'core', trackingType: 'reps', difficulty: 'intermediate', equipment: ['none'], ...o });
    const cat = [c('5', 'B'), c('2', 'A'), c('1', 'A'), c('9', 'C'), c('3', 'D'), c('0', '0', { equipment: ['barbell'] }), c('4', '0', { difficulty: 'advanced' }), c('6', '0', { primaryMuscle: 'chest' })];
    expect(harderVariations(ex, cat)).toEqual(['1', '2', '5']);
    expect(harderVariations({ ...ex, difficulty: 'advanced' }, cat)).toEqual([]);
  });

  it('raisedLoad compares prescriptions; every reason has text', () => {
    expect(raisedLoad(p({ targetWeightKg: 40 }), p({ targetWeightKg: 41 }))).toBe(true);
    expect(raisedLoad(p({ targetWeightKg: 40 }), p({ targetWeightKg: 36 }))).toBe(false);
    expect(raisedLoad(undefined, p({ targetWeightKg: 41 }))).toBe(false);
    expect(Object.keys(REASON_TEXT).sort()).toEqual(['ALL_SETS_COMPLETED', 'CAP_REACHED', 'COOLDOWN', 'HIGH_EFFORT', 'MISSED_TARGET', 'NOT_ENOUGH_HISTORY', 'NO_TARGET_SET', 'REPEATED_MISSES', 'REP_CEILING', 'VERY_HARD']);
  });
});
