import type { NestExpressApplication } from '@nestjs/platform-express';

import request from 'supertest';

import { createTestApp } from './helpers';
import { API_PREFIX, HTTP_METHODS, openapi, registeredRoutes, responseValidator, violations } from './support/openapi';

/**
 * Contract drift (both directions): every route the real app registers must be in
 * contracts/openapi.yaml, and every openapi operation must be served by a route. Boots the app
 * with a stub database, so it runs in every test run, not only with DB_TESTS.
 */
describe('routes ↔ contracts/openapi.yaml', () => {
  /** Routes deliberately outside the API contract. */
  const NOT_IN_CONTRACT = new Set([
    'get /health', // Render health check, unprefixed (docs/DEPLOY.md)
  ]);
  const isSwaggerUi = (route: string) => / \/docs(-json|-yaml)?(\/|$)/.test(route); // non-prod only (SWAGGER_ENABLED)

  let app: NestExpressApplication;
  let routes: string[];
  beforeAll(async () => {
    app = await createTestApp({ prisma: { $connect: async () => {}, $disconnect: async () => {} } });
    routes = registeredRoutes(app);
  });
  afterAll(() => app.close());

  const contractOps = () =>
    Object.entries(openapi.paths).flatMap(([path, item]) =>
      Object.keys(item)
        .filter((m) => (HTTP_METHODS as readonly string[]).includes(m))
        .map((m) => `${m} ${path}`),
    );
  const apiRoutes = () =>
    routes.filter((r) => !NOT_IN_CONTRACT.has(r) && !isSwaggerUi(r)).map((r) => {
      const [method, path] = r.split(' ');
      expect({ route: r, prefixed: path!.startsWith(`${API_PREFIX}/`) }).toEqual({ route: r, prefixed: true });
      return `${method} ${path!.slice(API_PREFIX.length)}`;
    });

  it('finds the routes (sanity: the enumeration works)', () => {
    expect(apiRoutes().length).toBeGreaterThan(50);
    expect(routes).toContain('get /health');
  });

  it('documents every registered route', () => {
    const documented = new Set(contractOps());
    expect(apiRoutes().filter((r) => !documented.has(r))).toEqual([]);
  });

  it('serves every documented operation', () => {
    const served = new Set(apiRoutes());
    expect(contractOps().filter((op) => !served.has(op))).toEqual([]);
  });

  it('registers each route once', () => {
    const all = apiRoutes();
    expect(all.filter((r, i) => all.indexOf(r) !== i)).toEqual([]);
  });

  describe('response checker (installed by createTestApp for every int/e2e test)', () => {
    it('validates documented bodies, including shared $ref responses', () => {
      const ok = responseValidator('get', '/xp/balance', 200)!;
      expect(ok({ total: 0, level: 1, xpIntoLevel: 0, xpForNextLevel: 100 })).toBe(true);
      expect(ok({ total: -1, level: 1, xpIntoLevel: 0 })).toBe(false);
      const unauthorized = responseValidator('get', '/xp/balance', 401)!;
      expect(unauthorized({ code: 'UNAUTHENTICATED', message: 'x', requestId: 'r' })).toBe(true);
      expect(unauthorized({ code: 'UNAUTHENTICATED' })).toBe(false);
    });

    it('knows bodiless and undocumented statuses', () => {
      expect(responseValidator('delete', '/me', 204)).toBeNull();
      expect(responseValidator('get', '/xp/balance', 418)).toBeUndefined();
    });

    it('checks a real response from the app without recording a violation', async () => {
      await request(app.getHttpServer()).get(`${API_PREFIX}/xp/balance`).expect(401);
      expect(violations).toEqual([]);
    });
  });
});
