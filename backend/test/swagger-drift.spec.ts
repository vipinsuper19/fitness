/**
 * Swagger (/docs, generated from the Nest DTO annotations) must describe the same request bodies and query
 * parameters as contracts/openapi.yaml. For every contract operation with a JSON request body or query
 * parameters, the Nest document's matching operation is resolved and compared field by field: property set,
 * required, type, nullable, enum and bounds (minLength/maxLength, minimum/maximum, exclusiveMinimum, minItems/maxItems).
 * Known intentional differences are listed in ALLOWED with the reason; anything else fails.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { NestExpressApplication } from '@nestjs/platform-express';
import { SwaggerModule, type OpenAPIObject } from '@nestjs/swagger';
import { parse } from 'yaml';

import { swaggerConfig } from '../src/setup';
import { createTestApp } from './helpers';

type S = Record<string, unknown>;
const CONTRACTS = join(__dirname, '../../contracts');

/**
 * `<METHOD> <path> <field path>: <aspect>` → why it differs on purpose.
 */
export const ALLOWED: Record<string, string> = {
  'POST /me/notification-devices: missing in Swagger': 'Planned endpoint (docs/specs/notification-sending.md); not implemented on the server yet.',
  'PATCH /me/notification-preferences body.reminders: only in contract':
    'Planned `reminders` settings (docs/specs/notification-sending.md); the server rejects the key today, so Swagger omits it.',
};

const files = new Map<string, S>();
const load = (f: string): S => {
  if (!files.has(f)) files.set(f, (f.endsWith('.json') ? JSON.parse(readFileSync(join(CONTRACTS, f), 'utf8')) : parse(readFileSync(join(CONTRACTS, f), 'utf8'))) as S);
  return files.get(f)!;
};
function deref(s: S, file: string, root: S): [S, string] {
  let cur = s;
  let f = file;
  for (let i = 0; i < 20 && typeof cur.$ref === 'string'; i++) {
    const [rf, ptr = ''] = (cur.$ref as string).split('#');
    if (rf) f = rf.replace(/^\.\//, '');
    const doc = rf ? load(f) : f ? load(f) : root;
    const rest = Object.fromEntries(Object.entries(cur).filter(([k]) => k !== '$ref'));
    let t: unknown = doc;
    for (const k of ptr.split('/').filter(Boolean)) t = (t as S)[k.replace(/~1/g, '/').replace(/~0/g, '~')];
    cur = { ...(t as S), ...rest };
  }
  return [cur, f];
}

interface Norm { types: string[]; nullable: boolean; enum?: string[]; bounds: Record<string, number>; props?: Record<string, Norm>; required?: string[]; items?: Norm }
const BOUNDS = ['minLength', 'maxLength', 'minimum', 'maximum', 'exclusiveMinimum', 'exclusiveMaximum', 'minItems', 'maxItems'];

function norm(input: S, file: string, root: S, depth = 0): Norm {
  let [s, f] = deref(input, file, root);
  // allOf: merge; oneOf/anyOf of [X, null]: X nullable.
  let nullable = s.nullable === true;
  for (const key of ['oneOf', 'anyOf'] as const) {
    const alts = s[key] as S[] | undefined;
    if (alts) {
      const non = alts.filter((a) => a.type !== 'null');
      if (non.length < alts.length) nullable = true;
      if (non.length === 1) { const [d, f2] = deref(non[0]!, f, root); s = { ...d, ...Object.fromEntries(Object.entries(s).filter(([k]) => k !== key)) }; f = f2; }
    }
  }
  if (Array.isArray(s.allOf)) {
    let merged: S = Object.fromEntries(Object.entries(s).filter(([k]) => k !== 'allOf'));
    for (const part of s.allOf as S[]) {
      const [d] = deref(part, f, root);
      merged = { ...d, ...merged, properties: { ...(d.properties as S), ...(merged.properties as S) }, required: [...((d.required as string[]) ?? []), ...((merged.required as string[]) ?? [])] };
    }
    s = merged;
  }
  const raw = ([] as string[]).concat((s.type as string | string[] | undefined) ?? []);
  if (raw.includes('null')) nullable = true;
  if (s.enum && (s.enum as unknown[]).includes(null)) nullable = true;
  let types = raw.filter((t) => t !== 'null').map((t) => (t === 'integer' ? 'integer' : t));
  if (!types.length && s.properties) types = ['object'];
  if (!types.length && s.enum) types = ['string'];
  const n: Norm = { types: types.sort(), nullable, bounds: {} };
  if (s.enum) n.enum = (s.enum as unknown[]).filter((e) => e !== null).map(String).sort();
  for (const b of BOUNDS) if (typeof s[b] === 'number') n.bounds[b] = s[b] as number;
  // OpenAPI 3.0 (Nest) spells an exclusive bound as `minimum: x, exclusiveMinimum: true`; 3.1 (contract) as `exclusiveMinimum: x`.
  for (const [ex, inc] of [['exclusiveMinimum', 'minimum'], ['exclusiveMaximum', 'maximum']] as const) {
    if (s[ex] === true && typeof s[inc] === 'number') { n.bounds[ex] = s[inc] as number; delete n.bounds[inc]; }
  }
  if (depth < 6 && s.properties) {
    n.props = Object.fromEntries(Object.entries(s.properties as Record<string, S>).map(([k, v]) => [k, norm(v, f, root, depth + 1)]));
    n.required = [...((s.required as string[]) ?? [])].sort();
  }
  if (depth < 6 && s.items) n.items = norm(s.items as S, f, root, depth + 1);
  return n;
}

function diff(c: Norm, n: Norm, at: string, out: string[]): void {
  if (c.types.join() !== n.types.join() && !(c.types.join() === 'integer' && n.types.join() === 'number')) out.push(`${at}: type contract=${c.types} nest=${n.types}`);
  if (c.nullable !== n.nullable) out.push(`${at}: nullable contract=${c.nullable} nest=${n.nullable}`);
  if ((c.enum ?? []).join() !== (n.enum ?? []).join()) out.push(`${at}: enum contract=${c.enum ?? '-'} nest=${n.enum ?? '-'}`);
  for (const b of BOUNDS) if (c.bounds[b] !== n.bounds[b]) out.push(`${at}: ${b} contract=${c.bounds[b] ?? '-'} nest=${n.bounds[b] ?? '-'}`);
  if (c.props || n.props) {
    const cp = c.props ?? {};
    const np = n.props ?? {};
    for (const k of new Set([...Object.keys(cp), ...Object.keys(np)])) {
      if (!cp[k]) { out.push(`${at}.${k}: only in nest`); continue; }
      if (!np[k]) { out.push(`${at}.${k}: only in contract`); continue; }
      const cr = (c.required ?? []).includes(k);
      const nr = (n.required ?? []).includes(k);
      if (cr !== nr) out.push(`${at}.${k}: required contract=${cr} nest=${nr}`);
      diff(cp[k]!, np[k]!, `${at}.${k}`, out);
    }
  }
  if (c.items && n.items) diff(c.items, n.items, `${at}[]`, out);
  else if (c.items || n.items) out.push(`${at}[]: items only in ${c.items ? 'contract' : 'nest'}`);
}

describe('Swagger (/docs) matches contracts/openapi request schemas', () => {
  let app: NestExpressApplication;
  let nest: OpenAPIObject;
  const contract = load('openapi.yaml');

  beforeAll(async () => {
    app = await createTestApp({ prisma: { $queryRaw: async () => [{ ok: 1 }], $disconnect: async () => undefined } });
    nest = SwaggerModule.createDocument(app, swaggerConfig());
  });
  afterAll(async () => {
    await app.close();
  });

  const nestOp = (method: string, path: string): S | undefined => {
    const want = path.replace(/\{[^}]+\}/g, '{}');
    for (const [p, ops] of Object.entries(nest.paths)) {
      const norm = p.replace(/^\/api\/v1/, '').replace(/\{[^}]+\}/g, '{}');
      if (norm === want) return (ops as S)[method] as S | undefined;
    }
    return undefined;
  };

  function drift(): string[] {
    const out: string[] = [];
    const root = nest as unknown as S;
    for (const [path, ops] of Object.entries(contract.paths as Record<string, S>)) {
      for (const [method, op] of Object.entries(ops)) {
        if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) continue;
        const cop = op as S;
        const label = `${method.toUpperCase()} ${path}`;
        const body = ((cop.requestBody as S | undefined)?.content as S | undefined)?.['application/json'] as S | undefined;
        const params = [...(((ops.parameters as S[]) ?? [])), ...(((cop.parameters as S[]) ?? []))].map((p) => deref(p, '', contract)[0]).filter((p) => p.in === 'query');
        if (!body && !params.length) continue;
        const n = nestOp(method, path);
        if (!n) { out.push(`${label}: missing in Swagger`); continue; }
        if (body) {
          const nb = ((n.requestBody as S | undefined)?.content as S | undefined)?.['application/json'] as S | undefined;
          if (!nb?.schema) out.push(`${label} body: missing in Swagger`);
          else diff(norm(body.schema as S, '', contract), norm(nb.schema as S, '', root), `${label} body`, out);
        }
        const nparams = ((n.parameters as S[]) ?? []).map((p) => deref(p, '', root)[0]).filter((p) => p.in === 'query');
        for (const p of params) {
          const q = nparams.find((x) => x.name === p.name);
          if (!q) { out.push(`${label} ?${p.name as string}: missing in Swagger`); continue; }
          if (!!p.required !== !!q.required) out.push(`${label} ?${p.name as string}: required contract=${!!p.required} nest=${!!q.required}`);
          diff(norm(p.schema as S, '', contract), norm(q.schema as S, '', root), `${label} ?${p.name as string}`, out);
        }
        for (const q of nparams) if (!params.some((p) => p.name === q.name)) out.push(`${label} ?${q.name as string}: only in nest`);
      }
    }
    return out;
  }

  it('has no drift outside the allowlist', () => {
    const found = drift();
    const unexpected = found.filter((d) => !(d in ALLOWED));
    if (process.env.PRINT_DRIFT) console.log(found.join('\n'));
    expect(unexpected).toEqual([]);
  });

  it('documents the standard error responses on every operation', () => {
    const ops = Object.values(nest.paths).flatMap((p) => Object.values(p as S) as S[]);
    expect(ops.length).toBeGreaterThan(50);
    for (const op of ops) expect(Object.keys(op.responses as S)).toEqual(expect.arrayContaining(['400', '429', '500']));
  });

  it('every allowlist entry still matches a real difference', () => {
    const found = new Set(drift());
    expect(Object.keys(ALLOWED).filter((k) => !found.has(k))).toEqual([]);
  });
});
