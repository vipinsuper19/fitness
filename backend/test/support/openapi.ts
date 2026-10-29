import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import Ajv2020, { type ValidateFunction } from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { parse } from 'yaml';

/** contracts/openapi.yaml, parsed once per jest worker. */
type Responses = Record<string, { $ref?: string; content?: Record<string, { schema?: unknown }> }>;
type Spec = { paths: Record<string, Record<string, { responses?: Responses }>>; components: { responses: Responses } };
const CONTRACTS_DIR = join(__dirname, '..', '..', '..', 'contracts');
export const OPENAPI_FILE = join(CONTRACTS_DIR, 'openapi.yaml');
const OPENAPI_ID = 'https://github.com/vipinsuper19/fitness/contracts/openapi.yaml';
export const openapi = parse(readFileSync(OPENAPI_FILE, 'utf8')) as Spec;

/**
 * x-internal allowlist: server routes that are deliberately NOT in openapi (not client APIs).
 * Anything that checks registered routes against the contract must exempt only these.
 * See docs/specs/notification-sending.md (internal tick endpoint).
 */
export const INTERNAL_ROUTES = ['post /internal/notifications/tick'] as const;

export const API_PREFIX = '/api/v1';

/**
 * Unprefixed routes that ARE in the contract: documented at the origin root with a path-level `servers` override
 * (docs/specs/health-deep.md). Checked like any other operation.
 */
export const ROOT_PATHS = ['/health/deep'] as const;
const isRootPath = (p: string): boolean => (ROOT_PATHS as readonly string[]).includes(p);
export const HTTP_METHODS = ['get', 'put', 'post', 'patch', 'delete'] as const;

/** Express `/a/:id/b` → OpenAPI `/a/{id}/b`. */
export const toOpenApiPath = (expressPath: string): string => expressPath.replace(/:([A-Za-z0-9_]+)/g, '{$1}');

/** Every route the real app registers, as "method /openapi/path" (prefix stripped); non-API routes kept as-is. */
export function registeredRoutes(app: NestExpressApplication): string[] {
  const express = app.getHttpAdapter().getInstance() as { router?: { stack: Layer[] }; _router?: { stack: Layer[] } };
  type Layer = { route?: { path: string; methods: Record<string, boolean> } };
  const stack = (express.router ?? express._router)!.stack;
  return stack.flatMap((layer) =>
    layer.route
      ? Object.keys(layer.route.methods)
          .filter((m) => (HTTP_METHODS as readonly string[]).includes(m))
          .map((m) => `${m} ${toOpenApiPath(layer.route!.path)}`)
      : [],
  );
}

let ajv: Ajv2020 | undefined;
const compiled = new Map<string, ValidateFunction | null>();

/**
 * The validator for one documented response body: null when the contract gives that status no
 * JSON body, undefined when the status isn't documented for the operation at all.
 */
export function responseValidator(method: string, path: string, status: number): ValidateFunction | null | undefined {
  const key = `${method} ${path} ${status}`;
  if (compiled.has(key)) return compiled.get(key);
  const responses = openapi.paths[path]?.[method]?.responses ?? {};
  const statusKey = [String(status), `${String(status)[0]}XX`, 'default'].find((k) => k in responses);
  if (!statusKey) return undefined;
  const entry = responses[statusKey]!;
  const refName = entry.$ref?.split('/').pop();
  const response = refName ? openapi.components.responses[refName]! : entry;
  let fn: ValidateFunction | null = null;
  if (response.content?.['application/json']?.schema !== undefined) {
    if (!ajv) {
      ajv = new Ajv2020({ allErrors: true, strict: false });
      addFormats(ajv);
      // The contract's base URI, so its './schemas/*.json' refs resolve to those files' own $id.
      ajv.addSchema({ ...(openapi as object), $id: OPENAPI_ID });
      for (const file of readdirSync(join(CONTRACTS_DIR, 'schemas'))) {
        ajv.addSchema(JSON.parse(readFileSync(join(CONTRACTS_DIR, 'schemas', file), 'utf8')) as object);
      }
    }
    const escaped = path.replace(/~/g, '~0').replace(/\//g, '~1');
    const base = refName ? `/components/responses/${refName}` : `/paths/${escaped}/${method}/responses/${statusKey}`;
    fn = ajv.compile({ $ref: `${OPENAPI_ID}#${base}/content/application~1json/schema` });
  }
  compiled.set(key, fn);
  return fn;
}

/** Contract violations seen by the response checker, drained by test/support/contract-setup.ts after each test. */
export const violations: string[] = [];

/**
 * Checks every JSON response the app sends against contracts/openapi.yaml: the matched route's
 * documented schema for that status. A 2xx that the contract doesn't document is a violation too.
 * Installed by createTestApp, so every int/e2e test that goes through the app is a contract test.
 */
export function installResponseChecker(app: NestExpressApplication): void {
  app.use((req: Req, res: Res, next: () => void) => {
    const json = res.json.bind(res);
    res.json = (body: unknown) => {
      try {
        checkResponse(req, res.statusCode, body);
      } catch (e) {
        violations.push(`${req.method} ${req.originalUrl}: response check failed: ${(e as Error).message}`);
      }
      return json(body);
    };
    next();
  });
}
type Req = { method: string; baseUrl?: string; route?: { path: string }; originalUrl: string };
type Res = { statusCode: number; json: (body: unknown) => unknown };

function checkResponse(req: Req, status: number, body: unknown): void {
  if (!req.route) return; // no matched route: 404s from express itself, not an operation
  const full = toOpenApiPath(`${req.baseUrl ?? ''}${req.route.path}`);
  if (!full.startsWith(`${API_PREFIX}/`) && !isRootPath(full)) return; // /health, /docs: not part of the API contract
  const path = isRootPath(full) ? full : full.slice(API_PREFIX.length);
  const method = req.method.toLowerCase();
  const validate = responseValidator(method, path, status);
  const where = `${req.method} ${req.originalUrl} → ${status} (${method} ${path})`;
  if (validate === undefined) {
    if (status < 300) violations.push(`${where}: status not documented in openapi.yaml`);
    return;
  }
  if (validate && !validate(JSON.parse(JSON.stringify(body)))) {
    violations.push(`${where}: ${ajvErrors(validate)}`);
  }
}
const ajvErrors = (v: ValidateFunction): string =>
  (v.errors ?? []).slice(0, 5).map((e) => `${e.instancePath || '/'} ${e.message ?? ''}`).join('; ');
