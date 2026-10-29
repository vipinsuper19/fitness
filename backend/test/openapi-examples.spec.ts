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

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ ...spec, $id: OPENAPI_ID });
for (const file of readdirSync(join(CONTRACTS_DIR, 'schemas'))) {
  ajv.addSchema(JSON.parse(readFileSync(join(CONTRACTS_DIR, 'schemas', file), 'utf8')) as object);
}

type Node = Record<string, unknown>;
type Found = { pointer: string; schemaPointer: string; name: string; value: unknown };
const esc = (k: string) => k.replace(/~/g, '~0').replace(/\//g, '~1');
const isObj = (v: unknown): v is Node => typeof v === 'object' && v !== null && !Array.isArray(v);

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

/**
 * Operations whose examples are owned by open PRs (#146 progression-suggestions, and the plan PATCH that applies
 * them). They already carry #141's examples, which are validated here; coverage for their other bodies follows
 * once those PRs land.
 */
const COVERAGE_EXEMPT = new Set(['get /workout-plans/{planId}/progression-suggestions', 'patch /workout-plans/{planId}']);

describe('contracts/openapi.yaml examples', () => {
  it('finds the examples', () => {
    expect(found.length).toBeGreaterThan(130);
  });

  it.each(found.map((f) => [`${f.pointer}`, f] as const))('%s matches its schema', (_p, f) => {
    const validate = ajv.getSchema(`${OPENAPI_ID}#${f.schemaPointer}`) ?? ajv.compile({ $ref: `${OPENAPI_ID}#${f.schemaPointer}` });
    const ok = validate(f.value);
    expect(ok ? [] : validate.errors).toEqual([]);
  });

  it('every JSON request body and response has an example', () => {
    const missing: string[] = [];
    const check = (label: string, media: Node | undefined) => {
      if (media && media.example === undefined && !isObj(media.examples)) missing.push(label);
    };
    const paths = spec.paths as Record<string, Record<string, Node>>;
    for (const [path, item] of Object.entries(paths)) {
      for (const [method, op] of Object.entries(item)) {
        if (!isObj(op) || !isObj(op.responses) || COVERAGE_EXEMPT.has(`${method} ${path}`)) continue;
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
