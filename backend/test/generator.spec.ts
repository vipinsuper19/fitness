/**
 * Rule-based plan generator: pure, deterministic, and its output is always a
 * valid WorkoutPlanDraft built only from exercises the user can do.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';

import { CatalogExercise, Equip, exercisesPerSession, generatePlan, GenerateRequest, Goal, Level } from '../src/workout-plans/generator';
import { EXERCISES } from '../prisma/seed-data/exercises';
import { seedId } from '../prisma/seed-data/ids';

const catalog: CatalogExercise[] = EXERCISES.map((e) => ({
  id: seedId('exercise', e.slug),
  slug: e.slug,
  name: e.name,
  primaryMuscle: e.primaryMuscle,
  secondaryMuscles: e.secondaryMuscles,
  equipment: e.equipment,
  trackingType: e.trackingType,
  difficulty: e.difficulty,
}));
const byId = new Map(catalog.map((e) => [e.id, e]));

const schema = JSON.parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'schemas', 'workout-plan-draft.schema.json'), 'utf8'));
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
const validateDraft = ajv.compile(schema);

const base: GenerateRequest = { goal: 'gain_muscle', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 45 };
const GOALS: Goal[] = ['lose_weight', 'gain_muscle', 'maintain', 'improve_endurance', 'workouts_per_week'];
const LEVELS: Level[] = ['beginner', 'intermediate', 'advanced'];
const RANK: Record<Level, number> = { beginner: 0, intermediate: 1, advanced: 2 };

describe('generatePlan (rule-based)', () => {
  it('is deterministic and independent of catalog order', () => {
    const a = generatePlan(base, catalog);
    const b = generatePlan(base, [...catalog].reverse());
    expect(b).toEqual(a);
    expect(a.generator).toBe('rule_based');
    expect(a.rationale).toMatch(/not AI/);
  });

  it('always returns a schema-valid draft with only usable exercises', () => {
    const kits: Equip[][] = [[], ['dumbbell'], ['dumbbell', 'bench'], ['barbell', 'bench', 'machine', 'cable', 'pull_up_bar', 'kettlebell', 'resistance_band', 'other']];
    for (const goal of GOALS)
      for (const experienceLevel of LEVELS)
        for (let daysPerWeek = 1; daysPerWeek <= 7; daysPerWeek += 1)
          for (const equipment of kits)
            for (const sessionMinutes of [15, 45, 180]) {
              const draft = generatePlan({ goal, experienceLevel, daysPerWeek, sessionMinutes, equipment }, catalog);
              if (!validateDraft(draft)) throw new Error(JSON.stringify(validateDraft.errors));
              expect(draft.workouts).toHaveLength(daysPerWeek);
              const days = draft.workouts.map((w) => w.dayOfWeek);
              expect(new Set(days).size).toBe(days.length);
              for (const w of draft.workouts) {
                expect(w.exercises.length).toBeGreaterThanOrEqual(3);
                expect(w.exercises.length).toBeLessThanOrEqual(exercisesPerSession(sessionMinutes));
                expect(new Set(w.exercises.map((e) => e.exerciseId)).size).toBe(w.exercises.length);
                for (const pe of w.exercises) {
                  const ex = byId.get(pe.exerciseId)!;
                  expect(ex).toBeDefined();
                  expect(ex.equipment.every((x) => x === 'none' || equipment.includes(x))).toBe(true);
                  expect(RANK[ex.difficulty]).toBeLessThanOrEqual(RANK[experienceLevel]);
                  if (ex.trackingType === 'reps' || ex.trackingType === 'reps_weight') {
                    expect(pe.repsMin).not.toBeNull();
                    expect(pe.durationSeconds).toBeNull();
                  } else {
                    expect(pe.durationSeconds).toBeGreaterThan(0);
                    expect(pe.repsMin).toBeNull();
                  }
                }
              }
            }
  });

  it('uses bodyweight only when no equipment is given', () => {
    const draft = generatePlan({ ...base, equipment: [] }, catalog);
    for (const w of draft.workouts) for (const e of w.exercises) expect(byId.get(e.exerciseId)!.equipment).toEqual(['none']);
    expect(draft.rationale).toMatch(/bodyweight only/);
  });

  it('picks the split and spreads days across the ISO week', () => {
    expect(generatePlan({ ...base, daysPerWeek: 3 }, catalog).workouts.map((w) => [w.dayOfWeek, w.name])).toEqual([
      [1, 'Full body A'], [3, 'Full body B'], [5, 'Full body A'],
    ]);
    expect(generatePlan({ ...base, daysPerWeek: 4 }, catalog).workouts.map((w) => w.name)).toEqual(['Upper body', 'Lower body', 'Upper body 2', 'Lower body 2']);
    expect(generatePlan({ ...base, daysPerWeek: 6 }, catalog).workouts.map((w) => w.dayOfWeek)).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it('follows the goal: heavier sets for muscle, short rest for endurance', () => {
    const muscle = generatePlan({ ...base, goal: 'gain_muscle', equipment: ['dumbbell', 'bench'] }, catalog);
    const repsEx = muscle.workouts[0]!.exercises.find((e) => e.repsMin !== null)!;
    expect([repsEx.repsMin, repsEx.repsMax, repsEx.restSeconds]).toEqual([8, 12, 90]);
    const endurance = generatePlan({ ...base, goal: 'improve_endurance' }, catalog);
    const e2 = endurance.workouts[0]!.exercises.find((e) => e.repsMin !== null)!;
    expect([e2.repsMin, e2.repsMax, e2.restSeconds]).toEqual([15, 20, 30]);
  });

  it('prefers loaded moves when equipment is available', () => {
    const draft = generatePlan({ ...base, equipment: ['dumbbell', 'bench'] }, catalog);
    const loaded = draft.workouts.flatMap((w) => w.exercises).filter((e) => !byId.get(e.exerciseId)!.equipment.includes('none'));
    expect(loaded.length).toBeGreaterThan(0);
  });

  it('scales exercises per session with time and defaults weeks to 4', () => {
    expect([15, 30, 45, 60, 90, 180].map(exercisesPerSession)).toEqual([3, 3, 4, 6, 8, 8]);
    expect(generatePlan(base, catalog).weeks).toBe(4);
    expect(generatePlan({ ...base, weeks: 12 }, catalog).weeks).toBe(12);
  });
});
