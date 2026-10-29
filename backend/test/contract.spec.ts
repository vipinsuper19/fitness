import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { parse } from 'yaml';

import { INTERNAL_ROUTES } from './support/openapi';

type Operation = {
  operationId: string;
  security?: unknown[];
  parameters?: Array<{ $ref?: string; name?: string }>;
  responses: Record<string, unknown>;
};
type PathItem = Record<string, Operation | unknown>;

const contractsDir = join(__dirname, '..', '..', 'contracts');
const spec = parse(readFileSync(join(contractsDir, 'openapi.yaml'), 'utf8')) as {
  paths: Record<string, PathItem>;
  components: { schemas: Record<string, { required?: string[] }> };
  security: unknown[];
};
const draftSchema = JSON.parse(
  readFileSync(join(contractsDir, 'schemas', 'workout-plan-draft.schema.json'), 'utf8'),
);

const WRITE_METHODS = ['post', 'put', 'patch', 'delete'];

// x-internal allowlist, defined with the other contract helpers so specs can import it without
// re-running this file's tests; re-exported here (#119).
export { INTERNAL_ROUTES };

function operations(): Array<{ path: string; method: string; op: Operation; pathItem: PathItem }> {
  return Object.entries(spec.paths).flatMap(([path, pathItem]) =>
    Object.entries(pathItem)
      .filter(([method]) => ['get', ...WRITE_METHODS].includes(method))
      .map(([method, op]) => ({ path, method, op: op as Operation, pathItem })),
  );
}
function paramRefs(op: Operation, pathItem: PathItem): string[] {
  const shared = (pathItem.parameters as Operation['parameters']) ?? [];
  return [...shared, ...(op.parameters ?? [])].map((p) => p.$ref ?? p.name ?? '');
}

describe('contracts/openapi.yaml', () => {
  // Route coverage (both directions) lives in contract-routes.spec.ts, generated from the app's own routes.

  it('keeps x-internal routes out of the contract, and only allowlists /internal routes', () => {
    for (const route of INTERNAL_ROUTES) {
      const [method = '', path = ''] = route.split(' ');
      expect(path.startsWith('/internal/')).toBe(true);
      expect(spec.paths[path]?.[method]).toBeUndefined();
    }
    expect(Object.keys(spec.paths).filter((p) => p.startsWith('/internal'))).toEqual([]);
  });

  it('accepts Idempotency-Key on every write except token and pure-compute calls', () => {
    const exempt = new Set(['login', 'refreshTokens', 'logout', 'generateWorkoutPlan']);
    for (const { method, op, pathItem } of operations()) {
      if (!WRITE_METHODS.includes(method) || exempt.has(op.operationId)) continue;
      const refs = paramRefs(op, pathItem);
      expect({ op: op.operationId, ok: refs.some((r) => r.includes('IdempotencyKey')) }).toEqual({
        op: op.operationId,
        ok: true,
      });
    }
  });

  it('requires Idempotency-Key where the client retries offline or XP can be awarded', () => {
    const required = [
      'startWorkoutSession', 'upsertSetLog', 'completeWorkoutSession',
      'createFoodLog', 'createWaterLog', 'createBodyMeasurement', 'joinChallenge',
    ];
    const byId = new Map(operations().map((o) => [o.op.operationId, o]));
    for (const id of required) {
      const entry = byId.get(id);
      expect(entry).toBeDefined();
      expect(paramRefs(entry!.op, entry!.pathItem)).toContain('#/components/parameters/IdempotencyKeyRequired');
    }
  });

  it('has no client endpoint that writes XP', () => {
    for (const { path, method } of operations()) {
      if (path.startsWith('/xp')) expect(method).toBe('get');
    }
  });

  it('requires a bearer token everywhere except register, login and refresh', () => {
    expect(spec.security).toEqual([{ bearerAuth: [] }]);
    const open = operations().filter(({ op }) => Array.isArray(op.security) && op.security.length === 0);
    expect(open.map(({ op }) => op.operationId).sort()).toEqual(['login', 'refreshTokens', 'register']);
  });

  it('never stores or replays token-bearing responses, and binds keys to method and path', () => {
    const info = (spec as unknown as { info: { description: string } }).info.description;
    expect(info).toMatch(/Token-bearing responses are never stored/);
    expect(info).toMatch(/different method or path/);
    expect(info).toMatch(/never re-awarded/);
    const byId = new Map(operations().map((o) => [o.op.operationId, o]));
    for (const id of ['login', 'refreshTokens']) {
      expect(paramRefs(byId.get(id)!.op, byId.get(id)!.pathItem).some((r) => r.includes('Idempotency'))).toBe(false);
    }
    const register = byId.get('register')!.op as Operation & { description?: string };
    expect(register.description).toMatch(/EMAIL_TAKEN/);
    expect(register.description).toMatch(/never stored or replayed/);
  });

  it('has no client endpoint that awards badges or completes challenges', () => {
    for (const { path, method, op } of operations()) {
      if (path.startsWith('/badges')) expect(method).toBe('get');
      if (path.startsWith('/challenges') && method !== 'get') expect(['joinChallenge', 'leaveChallenge']).toContain(op.operationId);
    }
  });

  it('documents the badge/challenge error codes, paging and XP sources', () => {
    const raw = readFileSync(join(contractsDir, 'openapi.yaml'), 'utf8');
    for (const code of ['CHALLENGE_ALREADY_JOINED', 'CHALLENGE_LIMIT_REACHED', 'CHALLENGE_NOT_ACTIVE']) expect(raw).toContain(code);
    const byId = new Map(operations().map((o) => [o.op.operationId, o]));
    const list = paramRefs(byId.get('listChallenges')!.op, byId.get('listChallenges')!.pathItem);
    expect(list).toEqual(expect.arrayContaining(['#/components/parameters/Cursor', '#/components/parameters/Limit']));
    const sources = (spec.components.schemas.XpSourceType as unknown as { enum: string[] }).enum;
    expect(sources).toEqual(expect.arrayContaining(['workout_session', 'adjustment', 'badge', 'challenge']));
    const challenge = spec.components.schemas.Challenge!;
    expect(challenge.required).toEqual(expect.arrayContaining(['status', 'progress', 'target', 'windowStart', 'windowEnd', 'timezone', 'xpAwarded']));
  });

  it('uses one error shape { code, message, details?, requestId }', () => {
    expect(spec.components.schemas.Error?.required).toEqual(['code', 'message', 'requestId']);
  });
});

describe('contracts/schemas/workout-plan-draft.schema.json', () => {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  const validate = ajv.compile(draftSchema);
  const draft = {
    name: 'Beginner full body, 3 days',
    weeks: 4,
    generator: 'rule_based',
    rationale: 'Three full-body days with a rest day between them.',
    workouts: [
      {
        dayOfWeek: 1,
        name: 'Full body A',
        exercises: [
          { exerciseId: '7c9e6679-7425-40de-944b-e07fc1f90ae7', order: 0, sets: 3, repsMin: 8, repsMax: 12, restSeconds: 90 },
        ],
      },
    ],
  };

  it('accepts a rule-based draft', () => {
    expect(validate(draft)).toBe(true);
  });

  it('rejects unknown fields and out-of-range values from any future generator', () => {
    expect(validate({ ...draft, apiKey: 'x' })).toBe(false);
    expect(validate({ ...draft, workouts: [{ ...draft.workouts[0], dayOfWeek: 8 }] })).toBe(false);
    expect(validate({ ...draft, generator: 'gpt' })).toBe(false);
  });
});

describe('progression-suggestions examples (Android)', () => {
  const doc = spec as unknown as {
    paths: Record<string, Record<string, { responses: Record<string, { content?: Record<string, { examples?: Record<string, { value: unknown }> }> }>; requestBody?: { content: Record<string, { examples?: Record<string, { value: Record<string, unknown> }> }> } }>>;
    components: { schemas: Record<string, unknown> };
  };
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);
  const defs = JSON.parse(JSON.stringify(doc.components.schemas).replaceAll('#/components/schemas/', '#/$defs/'));
  const schemaFor = (name: string) => ajv.compile({ $defs: defs, $ref: `#/$defs/${name}` });

  it('every GET 200 example validates against ProgressionSuggestions and uses the server reason texts', async () => {
    const { REASON_TEXT } = await import('../src/workout-plans/progression.rules');
    const examples = doc.paths['/workout-plans/{planId}/progression-suggestions']!.get!.responses['200']!.content!['application/json']!.examples!;
    expect(Object.keys(examples).length).toBeGreaterThanOrEqual(2);
    const validate = schemaFor('ProgressionSuggestions');
    for (const [name, ex] of Object.entries(examples)) {
      expect({ name, ok: validate(ex.value), errors: validate.errors }).toEqual({ name, ok: true, errors: null });
      for (const s of (ex.value as { suggestions: { reason: keyof typeof REASON_TEXT; reasonText: string }[] }).suggestions) expect(s.reasonText).toBe(REASON_TEXT[s.reason]);
    }
  });

  it('the PATCH apply example is a valid plan draft that carries the GET example\'s suggested values', () => {
    const ex = doc.paths['/workout-plans/{planId}']!.patch!.requestBody!.content['application/json']!.examples!.applySuggestions!.value;
    const { baseVersion, ...draft } = ex;
    const validate = ajv.compile(draftSchema);
    expect(validate({ ...draft, name: 'Push / Pull' })).toBe(true);
    const mixed = doc.paths['/workout-plans/{planId}/progression-suggestions']!.get!.responses['200']!.content!['application/json']!.examples!.mixed!.value as {
      planVersion: number; suggestions: { order: number; suggested: Record<string, unknown> | null; current: Record<string, unknown> }[];
    };
    expect(baseVersion).toBe(mixed.planVersion);
    const sent = (draft as { workouts: { exercises: Record<string, unknown>[] }[] }).workouts[0]!.exercises;
    for (const s of mixed.suggestions) expect(sent.find((e) => e.order === s.order)).toMatchObject(s.suggested ?? s.current);
  });
});
