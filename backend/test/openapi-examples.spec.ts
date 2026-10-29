import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { parse } from 'yaml';

/**
 * Every example in contracts/openapi.yaml must match the schema it illustrates, so examples can't drift from the
 * contract. Also: every JSON request body and response has at least one example, and example emails use the
 * reserved example.invalid domain (no real data).
 */
const CONTRACTS_DIR = join(__dirname, '..', '..', 'contracts');
const OPENAPI_ID = 'https://github.com/vipinsuper19/fitness/contracts/openapi.yaml';
const spec = parse(readFileSync(join(CONTRACTS_DIR, 'openapi.yaml'), 'utf8')) as Record<string, unknown>;

type Node = Record<string, unknown>;
type Found = { pointer: string; schemaPointer: string; name: string; value: unknown };
const esc = (k: string) => k.replace(/~/g, '~0').replace(/\//g, '~1');
const isObj = (v: unknown): v is Node => typeof v === 'object' && v !== null && !Array.isArray(v);

/*
 * Strict mode, for examples only: an example may not contain a field its schema doesn't define, so a misspelled
 * field fails. The contract itself stays open (clients must ignore unknown fields); this runs on a cloned copy.
 * Every object schema that says nothing about extra properties gets `unevaluatedProperties: false` (not
 * `additionalProperties`, which can't see properties defined in sibling allOf members). allOf members are left open
 * (their parent closes the combined object); a `$ref` to a component inside allOf points at an open copy of it under
 * components/x-open-schemas. oneOf/anyOf branches are closed on their own.
 */
const SCHEMA_MAP_KEYS = ['properties', 'patternProperties', '$defs', 'dependentSchemas'];
const SCHEMA_LIST_KEYS = ['allOf', 'anyOf', 'oneOf', 'prefixItems'];
const SCHEMA_KEYS = ['items', 'not', 'if', 'then', 'else', 'contains', 'additionalProperties', 'unevaluatedProperties', 'propertyNames', 'unevaluatedItems'];
const COMPONENT_REF = '#/components/schemas/';

function objectLike(s: Node): boolean {
  const t = s.type;
  return t === 'object' || (Array.isArray(t) && t.includes('object')) || isObj(s.properties) || Array.isArray(s.required) || ['allOf', 'anyOf', 'oneOf'].some((k) => Array.isArray(s[k]));
}

/** Strict clone of a schema; `close` false leaves this node itself open (an allOf member). */
function closeSchema(s: unknown, close = true): unknown {
  if (!isObj(s)) return s;
  const out: Node = { ...s };
  for (const k of SCHEMA_MAP_KEYS) if (isObj(s[k])) out[k] = Object.fromEntries(Object.entries(s[k] as Node).map(([n, v]) => [n, closeSchema(v)]));
  for (const k of SCHEMA_LIST_KEYS) if (Array.isArray(s[k])) out[k] = (s[k] as unknown[]).map((v) => (k === 'allOf' ? openMember(v) : closeSchema(v)));
  for (const k of SCHEMA_KEYS) if (isObj(s[k])) out[k] = closeSchema(s[k]);
  const saysNothing = !('additionalProperties' in s) && !('unevaluatedProperties' in s) && !('patternProperties' in s) && !('$ref' in s);
  if (close && saysNothing && objectLike(s)) out.unevaluatedProperties = false;
  return out;
}
function openMember(v: unknown): unknown {
  if (isObj(v) && typeof v.$ref === 'string' && v.$ref.startsWith(COMPONENT_REF)) return { ...v, $ref: `#/components/x-open-schemas/${v.$ref.slice(COMPONENT_REF.length)}` };
  return closeSchema(v, false);
}
/** Closes every `schema` in paths/parameters/headers/responses, and every component schema (plus open copies). */
function strictSpec(doc: Node): Node {
  const walk = (n: unknown): unknown => {
    if (Array.isArray(n)) return n.map(walk);
    if (!isObj(n)) return n;
    return Object.fromEntries(Object.entries(n).map(([k, v]) => [k, k === 'schema' ? closeSchema(v) : k === 'example' || k === 'examples' ? v : walk(v)]));
  };
  const out = walk(doc) as Node & { components: Node };
  const schemas = (doc.components as Node).schemas as Node;
  out.components.schemas = Object.fromEntries(Object.entries(schemas).map(([k, v]) => [k, closeSchema(v)]));
  out.components['x-open-schemas'] = Object.fromEntries(Object.entries(schemas).map(([k, v]) => [k, closeSchema(v, false)]));
  return out;
}

function makeAjv(strict: boolean): Ajv2020 {
  const a = new Ajv2020({ allErrors: true, strict: false });
  addFormats(a);
  a.addSchema({ ...(strict ? strictSpec(spec) : spec), $id: OPENAPI_ID });
  for (const file of readdirSync(join(CONTRACTS_DIR, 'schemas'))) {
    const schema = JSON.parse(readFileSync(join(CONTRACTS_DIR, 'schemas', file), 'utf8')) as object;
    a.addSchema((strict ? closeSchema(schema) : schema) as object);
  }
  return a;
}
const ajv = makeAjv(true);
const laxAjv = makeAjv(false);
const validatorFor = (a: Ajv2020, schemaPointer: string) => a.getSchema(`${OPENAPI_ID}#${schemaPointer}`) ?? a.compile({ $ref: `${OPENAPI_ID}#${schemaPointer}` });

/** Walks the document and collects every example with the pointer of the schema it belongs to. */
function collect(node: unknown, pointer: string, out: Found[], inSchema: boolean): void {
  if (Array.isArray(node)) return node.forEach((v, i) => collect(v, `${pointer}/${i}`, out, inSchema));
  if (!isObj(node)) return;
  if (!inSchema && 'schema' in node) {
    // Media type or parameter object: example / examples.{name}.value illustrate `schema`.
    const schemaPointer = `${pointer}/schema`;
    if (node.example !== undefined) out.push({ pointer: `${pointer}/example`, schemaPointer, name: 'example', value: node.example });
    if (isObj(node.examples)) {
      for (const [name, ex] of Object.entries(node.examples)) {
        out.push({ pointer: `${pointer}/examples/${esc(name)}`, schemaPointer, name, value: (ex as Node).value });
      }
    }
  }
  if (inSchema) {
    // Schema object: `examples` is an array of instances (JSON Schema 2020-12), `example` the OAS 3.0 form.
    if (Array.isArray(node.examples)) node.examples.forEach((v, i) => out.push({ pointer: `${pointer}/examples/${i}`, schemaPointer: pointer, name: `#${i}`, value: v }));
    if (node.example !== undefined) out.push({ pointer: `${pointer}/example`, schemaPointer: pointer, name: 'example', value: node.example });
  }
  for (const [k, v] of Object.entries(node)) {
    if (k === 'example' || k === 'examples') continue;
    const childInSchema = inSchema || k === 'schema' || (pointer === '/components' && k === 'schemas');
    // Under components/schemas each value is a schema; inside a schema, `properties`/`$defs` maps hold schemas too.
    collect(v, `${pointer}/${esc(k)}`, out, childInSchema);
  }
}

const found: Found[] = [];
collect(spec, '', found, false);

describe('contracts/openapi.yaml examples', () => {
  it('finds the examples', () => {
    expect(found.length).toBeGreaterThan(130);
  });

  it.each(found.map((f) => [`${f.pointer}`, f] as const))('%s matches its schema (no unknown fields)', (_p, f) => {
    const validate = validatorFor(ajv, f.schemaPointer);
    const ok = validate(f.value);
    expect(ok ? [] : validate.errors).toEqual([]);
  });

  describe('unknown fields in examples fail (negative tests)', () => {
    const REGISTER_201 = '/paths/~1auth~1register/post/responses/201/content/application~1json';
    const example = () => JSON.parse(JSON.stringify(found.find((f) => f.pointer === `${REGISTER_201}/example`)!.value)) as Node;

    it('a misspelled top-level field fails, even though AuthResponse is an allOf', () => {
      const bad = { ...example(), acessToken: 'x' };
      expect(laxAjv.compile({ $ref: `${OPENAPI_ID}#${REGISTER_201}/schema` })(bad)).toBe(true); // the open contract allows it
      expect(validatorFor(ajv, `${REGISTER_201}/schema`)(bad)).toBe(false);
    });

    it('a misspelled nested field fails ($ref inside an allOf member)', () => {
      const bad = example();
      (bad.user as Node).emial = 'x@example.invalid';
      expect(validatorFor(ajv, `${REGISTER_201}/schema`)(bad)).toBe(false);
    });

    it('a misspelled field fails on every object example in the contract', () => {
      const leaky = found.filter((f) => isObj(f.value) && validatorFor(ajv, f.schemaPointer)({ ...f.value, zzMisspelled: 1 })).map((f) => f.pointer);
      expect(leaky).toEqual([]);
    });

    it('the strict copy keeps every example valid where the open contract says so, and leaves Error.details open', () => {
      const rateLimited = { code: 'RATE_LIMITED', message: 'm', requestId: 'r', details: { retryAfterSeconds: 1, futureKey: true } };
      expect(validatorFor(ajv, '/components/schemas/Error')(rateLimited)).toBe(true);
    });
  });

  it('every JSON request body and response has an example', () => {
    const missing: string[] = [];
    const check = (label: string, media: Node | undefined) => {
      if (media && media.example === undefined && !isObj(media.examples)) missing.push(label);
    };
    const paths = spec.paths as Record<string, Record<string, Node>>;
    for (const [path, item] of Object.entries(paths)) {
      for (const [method, op] of Object.entries(item)) {
        if (!isObj(op) || !isObj(op.responses)) continue;
        const body = (op.requestBody as Node | undefined)?.content as Record<string, Node> | undefined;
        check(`${method} ${path} request`, body?.['application/json']);
        for (const [status, res] of Object.entries(op.responses as Record<string, Node>)) {
          check(`${method} ${path} ${status}`, (res.content as Record<string, Node> | undefined)?.['application/json']);
        }
      }
    }
    const responses = (spec.components as Node).responses as Record<string, Node>;
    for (const [name, res] of Object.entries(responses)) {
      check(`components/responses/${name}`, (res.content as Record<string, Node> | undefined)?.['application/json']);
    }
    expect(missing).toEqual([]);
  });

  it('uses only example.invalid email addresses', () => {
    const emails: string[] = [];
    const walk = (v: unknown): void => {
      if (typeof v === 'string') emails.push(...(v.match(/[^\s@'"`]+@[^\s@'"`]+\.[a-z]+/gi) ?? []));
      else if (Array.isArray(v)) v.forEach(walk);
      else if (isObj(v)) Object.values(v).forEach(walk);
    };
    found.forEach((f) => walk(f.value));
    expect(emails.length).toBeGreaterThan(0);
    expect(emails.filter((e) => !e.endsWith('@example.invalid'))).toEqual([]);
  });
});
