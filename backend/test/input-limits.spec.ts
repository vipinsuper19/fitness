/**
 * Input limits (docs/specs/input-limits.md), checked through the global ValidationPipe without a database:
 * over-max strings, arrays and numbers are 400 VALIDATION_FAILED naming the field, the documented maximum
 * itself is accepted, and every paged list caps `limit` at 100.
 */
import { BadRequestException, type Type } from '@nestjs/common';

import { ChallengeJoinDto, ChallengeListQuery } from '../src/achievements/achievements.dto';
import { RegisterDto } from '../src/auth/dto/auth.dto';
import { PageQuery } from '../src/common/page.dto';
import { globalValidationPipe } from '../src/common/validation';
import { ExerciseListQuery } from '../src/exercises/exercises.dto';
import { FoodCreateDto, FoodSearchQuery } from '../src/nutrition/nutrition.dto';
import { ExerciseProgressListQuery } from '../src/progress/strength.dto';
import { MeasurementListQuery } from '../src/progress/progress.dto';
import { PlanGenerateDto, PlanListQuery, PlanVersionQuery, WorkoutPlanDraftDto, WorkoutPlanUpdateDto } from '../src/workout-plans/plans.dto';
import { HistoryQuery, SessionStartDto, SetLogUpsertDto } from '../src/workout-sessions/sessions.dto';

const pipe = globalValidationPipe();
/** Field-error map for this input ({} when valid). */
async function fields(metatype: Type<unknown>, value: object, type: 'body' | 'query' = 'body'): Promise<Record<string, string>> {
  try {
    await pipe.transform(value, { type, metatype });
    return {};
  } catch (e) {
    expect(e).toBeInstanceOf(BadRequestException);
    const body = (e as BadRequestException).getResponse() as { code: string; details: { fields: Record<string, string> } };
    expect(body.code).toBe('VALIDATION_FAILED');
    return body.details.fields;
  }
}

const EX = '0b9d3c2e-1f4a-4c55-9a7e-2d6f8e1a7b01';
const exercise = (o: object = {}) => ({ exerciseId: EX, order: 0, sets: 3, repsMin: 8, repsMax: 12, targetWeightKg: 40, durationSeconds: null, restSeconds: 90, notes: null, ...o });
const workout = (o: object = {}) => ({ dayOfWeek: 1, name: 'Push', exercises: [exercise()], ...o });
const draft = (o: object = {}) => ({ name: 'Push pull legs', weeks: 4, generator: 'custom', workouts: [workout()], ...o });

describe('input limits', () => {
  it('a realistic full plan (7 days × 30 exercises, longest names and notes) is accepted', async () => {
    const days = [1, 2, 3, 4, 5, 6, 7].map((d) => workout({ dayOfWeek: d, name: 'n'.repeat(80), exercises: Array.from({ length: 30 }, (_, i) => exercise({ order: i, sets: 20, notes: 'x'.repeat(500) })) }));
    expect(await fields(WorkoutPlanDraftDto, draft({ name: 'p'.repeat(80), workouts: days, rationale: 'r'.repeat(1000) }))).toEqual({});
  });

  it.each([
    ['plan name over 80', WorkoutPlanDraftDto, draft({ name: 'p'.repeat(81) }), 'name'],
    ['exercise notes over 500', WorkoutPlanDraftDto, draft({ workouts: [workout({ exercises: [exercise({ notes: 'x'.repeat(501) })] })] }), 'workouts.0.exercises.0.notes'],
    ['displayName over 60', RegisterDto, { email: 'asha@example.invalid', password: 'correct horse battery', displayName: 'a'.repeat(61) }, 'displayName'],
    ['food name over 120', FoodCreateDto, { name: 'f'.repeat(121), servingSize: 1, servingUnit: 'plate', nutrients: { energyKcal: 1, proteinG: 1, carbsG: 1, fatG: 1 } }, 'name'],
    ['session notes over 1000', SessionStartDto, { notes: 'n'.repeat(1001) }, 'notes'],
  ])('over-max string: %s is VALIDATION_FAILED', async (_n, dto, body, field) => {
    expect(Object.keys(await fields(dto, body))).toContain(field);
  });

  it.each([
    ['31 exercises in a workout', WorkoutPlanDraftDto, draft({ workouts: [workout({ exercises: Array.from({ length: 31 }, (_, i) => exercise({ order: i })) })] }), 'workouts.0.exercises'],
    ['8 workouts in a plan', WorkoutPlanDraftDto, draft({ workouts: [1, 2, 3, 4, 5, 6, 7, 1].map((d) => workout({ dayOfWeek: d })) }), 'workouts'],
    ['11 equipment items', PlanGenerateDto, { goal: 'muscle_gain', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 45, equipment: Array(11).fill('dumbbell') }, 'equipment'],
  ])('over-max array: %s is VALIDATION_FAILED', async (_n, dto, body, field) => {
    expect(Object.keys(await fields(dto, body))).toContain(field);
  });

  it.each([
    ['sets per exercise over 20', WorkoutPlanDraftDto, draft({ workouts: [workout({ exercises: [exercise({ sets: 21 })] })] }), 'workouts.0.exercises.0.sets'],
    ['exercise order over 1000', WorkoutPlanDraftDto, draft({ workouts: [workout({ exercises: [exercise({ order: 1001 })] })] }), 'workouts.0.exercises.0.order'],
    ['baseVersion over 1000000', WorkoutPlanUpdateDto, { ...draft(), name: undefined, baseVersion: 1_000_001 }, 'baseVersion'],
    ['challenge target over 1000', ChallengeJoinDto, { templateCode: 'WEEKLY_WORKOUTS', target: 1001 }, 'target'],
    ['setIndex over 50', SetLogUpsertDto, { clientSetId: EX, exerciseId: EX, setIndex: 51, completed: true }, 'setIndex'],
  ])('over-max number: %s is VALIDATION_FAILED', async (_n, dto, body, field) => {
    expect(Object.keys(await fields(dto, body))).toContain(field);
  });

  it('new caps accept their maximum', async () => {
    expect(await fields(WorkoutPlanUpdateDto, { weeks: 4, generator: 'custom', workouts: [workout({ exercises: [exercise({ order: 1000 })] })], baseVersion: 1_000_000 })).toEqual({});
    expect(await fields(ChallengeJoinDto, { templateCode: 'WEEKLY_WORKOUTS', target: 1000 })).toEqual({});
    expect(await fields(PlanVersionQuery, { version: '1000000' }, 'query')).toEqual({});
  });

  it.each([PageQuery, HistoryQuery, ChallengeListQuery, FoodSearchQuery, ExerciseListQuery, PlanListQuery, MeasurementListQuery, ExerciseProgressListQuery])(
    '%p caps limit at 100', async (dto) => {
      expect(await fields(dto as Type<unknown>, { limit: '101' }, 'query')).toHaveProperty('limit');
      expect(await fields(dto as Type<unknown>, { limit: '0' }, 'query')).toHaveProperty('limit');
      expect(await fields(dto as Type<unknown>, { limit: '100' }, 'query')).not.toHaveProperty('limit');
    },
  );
});
