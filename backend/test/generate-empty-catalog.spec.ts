import { HttpStatus, Logger } from '@nestjs/common';

import { ApiError } from '../src/common/api-error';
import { HttpExceptionFilter } from '../src/common/http-exception.filter';
import type { PrismaService } from '../src/prisma/prisma.service';
import type { CatalogExercise } from '../src/workout-plans/generator';
import type { PlanGenerateDto } from '../src/workout-plans/plans.dto';
import { PlansService } from '../src/workout-plans/plans.service';

const req = { goal: 'maintain', experienceLevel: 'beginner', daysPerWeek: 3, sessionMinutes: 30, weeks: 4 } as PlanGenerateDto;
const service = (catalog: CatalogExercise[]) => new PlansService({ exercise: { findMany: async () => catalog } } as unknown as PrismaService);
const ex = (over: Partial<CatalogExercise>): CatalogExercise => ({
  id: '00000000-0000-4000-8000-000000000001', slug: 'push-up', name: 'Push-up', primaryMuscle: 'chest', secondaryMuscles: [],
  equipment: ['none'], trackingType: 'reps', difficulty: 'beginner', ...over,
} as CatalogExercise);

async function failure(p: Promise<unknown>): Promise<ApiError> {
  try { await p; } catch (e) { return e as ApiError; }
  throw new Error('expected an error');
}

describe('POST /workout-plans/generate without usable exercises', () => {
  let error: jest.SpyInstance; let warn: jest.SpyInstance;
  beforeEach(() => {
    error = jest.spyOn(Logger.prototype, 'error').mockImplementation(() => undefined);
    warn = jest.spyOn(Logger.prototype, 'warn').mockImplementation(() => undefined);
  });
  afterEach(() => jest.restoreAllMocks());

  it('empty (unseeded) catalog -> 503 EXERCISE_CATALOG_EMPTY and an error log', async () => {
    const e = await failure(service([]).generate(req));
    expect(e).toBeInstanceOf(ApiError);
    expect(e.getStatus()).toBe(HttpStatus.SERVICE_UNAVAILABLE);
    expect(e.getResponse()).toMatchObject({ code: 'EXERCISE_CATALOG_EMPTY' });
    expect(error).toHaveBeenCalledWith(expect.stringContaining('exercise catalog is empty'));
  });

  it('catalog with nothing for this level/equipment -> 422 NO_MATCHING_EXERCISES and a warn log', async () => {
    const e = await failure(service([ex({ difficulty: 'advanced' }), ex({ id: '00000000-0000-4000-8000-000000000002', slug: 'bench', equipment: ['barbell'] })]).generate(req));
    expect(e.getStatus()).toBe(HttpStatus.UNPROCESSABLE_ENTITY);
    expect(e.getResponse()).toMatchObject({ code: 'NO_MATCHING_EXERCISES', details: { experienceLevel: 'beginner', equipment: [] } });
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('no exercises match level=beginner'));
    expect(error).not.toHaveBeenCalled();
  });

  it('a usable catalog still returns a draft', async () => {
    const d = await service([ex({})]).generate(req);
    expect(d.workouts.length).toBeGreaterThan(0);
  });

  it('the error filter keeps the 503 code (message is the generic 5xx one)', () => {
    let status = 0; let body: Record<string, unknown> = {};
    const res = { status: (s: number) => { status = s; return res; }, json: (b: Record<string, unknown>) => { body = b; }, getHeader: () => undefined, setHeader: () => undefined };
    const host = { switchToHttp: () => ({ getRequest: () => ({ requestId: 'r1' }), getResponse: () => res }) };
    new HttpExceptionFilter().catch(new ApiError(HttpStatus.SERVICE_UNAVAILABLE, 'EXERCISE_CATALOG_EMPTY', 'x'), host as never);
    expect(status).toBe(503);
    expect(body).toMatchObject({ code: 'EXERCISE_CATALOG_EMPTY', requestId: 'r1' });
  });
});
