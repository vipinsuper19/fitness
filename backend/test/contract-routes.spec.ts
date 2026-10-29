import type { NestExpressApplication } from '@nestjs/platform-express';

import request from 'supertest';

import { createTestApp } from './helpers';
import { API_PREFIX, HTTP_METHODS, INTERNAL_ROUTES, ROOT_PATHS, openapi, registeredRoutes, responseValidator, violations } from './support/openapi';

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

  /**
   * Documented in openapi ahead of the implementation (docs/specs/notification-sending.md, device
   * tokens), so clients can build against them. Exempt only from 'serves every documented
   * operation'. Remove each entry when its route is implemented.
   */
  const DOCUMENTED_NOT_YET_IMPLEMENTED = new Set([
    'post /me/notification-devices',
    'delete /me/notification-devices/{deviceId}',
  ]);
  const internal = new Set<string>(INTERNAL_ROUTES);

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
      if ((ROOT_PATHS as readonly string[]).includes(path!)) return r; // documented at the origin root
      expect({ route: r, prefixed: path!.startsWith(`${API_PREFIX}/`) }).toEqual({ route: r, prefixed: true });
      return `${method} ${path!.slice(API_PREFIX.length)}`;
    });

  it('finds the routes (sanity: the enumeration works)', () => {
    expect(apiRoutes().length).toBeGreaterThan(50);
    expect(routes).toContain('get /health');
  });

  it('root-level operations (/health/deep) are registered and documented with a root servers override', () => {
    expect(routes).toContain('get /health/deep');
    for (const p of ROOT_PATHS) {
      const servers = (openapi.paths[p] as unknown as { servers?: { url: string }[] } | undefined)?.servers ?? [];
      expect({ p, servers: servers.length > 0 && servers.every((s) => !s.url.includes(API_PREFIX)) }).toEqual({ p, servers: true });
    }
  });

  it('documents every registered route', () => {
    const documented = new Set(contractOps());
    expect(apiRoutes().filter((r) => !documented.has(r) && !internal.has(r))).toEqual([]);
  });

  it('serves every documented operation', () => {
    const served = new Set(apiRoutes());
    expect(contractOps().filter((op) => !served.has(op) && !DOCUMENTED_NOT_YET_IMPLEMENTED.has(op))).toEqual([]);
    // The list stays honest: every entry is documented and not served yet.
    for (const op of DOCUMENTED_NOT_YET_IMPLEMENTED) expect({ op, documented: contractOps().includes(op), served: served.has(op) }).toEqual({ op, documented: true, served: false });
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
