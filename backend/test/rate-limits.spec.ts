import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { METHOD_METADATA, PATH_METADATA, GUARDS_METADATA } from '@nestjs/common/constants';
import { RequestMethod } from '@nestjs/common';
import { ModulesContainer } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { ThrottlerGuard } from '@nestjs/throttler';
import request from 'supertest';

import { THROTTLES, THROTTLE_NAMES, UserThrottlerGuard, type ThrottleName } from '../src/common/rate-limit';
import { envSchema } from '../src/config/env';
import { createTestApp } from './helpers';
import { API_PREFIX, INTERNAL_ROUTES, openapi, registeredRoutes } from './support/openapi';

/**
 * Rate-limit audit: every write route (POST/PUT/PATCH/DELETE), auth routes included, must use exactly one named,
 * env-configurable throttle (src/common/rate-limit.ts, `*_RATE_LIMIT_PER_MINUTE`), and document 429.
 * Routes are derived from the running app's controllers, so a new write route fails here until it is throttled.
 */
const WRITE = new Set(['post', 'put', 'patch', 'delete']);

/**
 * Write routes deliberately without a named throttle. Each entry needs a reason; keep it short.
 * - The internal tick is called only by the scheduler with a shared secret, never by clients (docs/specs/notification-sending.md).
 */
const EXEMPT: Record<string, string> = Object.fromEntries(INTERNAL_ROUTES.map((r) => [r, 'internal scheduler endpoint, secret-authenticated']));

type Route = { route: string; throttles: ThrottleName[]; guarded: boolean; perUser: boolean };

function throttledRoutes(app: NestExpressApplication): Route[] {
  const out: Route[] = [];
  for (const mod of app.get(ModulesContainer).values()) {
    for (const wrapper of mod.controllers.values()) {
      const cls = wrapper.metatype as (new (...a: unknown[]) => unknown) | undefined;
      if (!cls) continue;
      const base = String(Reflect.getMetadata(PATH_METADATA, cls) ?? '');
      for (const key of Object.getOwnPropertyNames(cls.prototype)) {
        const handler = cls.prototype[key] as object;
        if (key === 'constructor' || typeof handler !== 'function' || Reflect.getMetadata(PATH_METADATA, handler) === undefined) continue;
        const method = RequestMethod[Reflect.getMetadata(METHOD_METADATA, handler) as number]!.toLowerCase();
        const path = ['', base, String(Reflect.getMetadata(PATH_METADATA, handler))].join('/').replace(/\/+/g, '/').replace(/(.)\/$/, '$1');
        const route = `${method} ${path.replace(/:([A-Za-z0-9_]+)/g, '{$1}')}`;
        const guards = [...(Reflect.getMetadata(GUARDS_METADATA, cls) ?? []), ...(Reflect.getMetadata(GUARDS_METADATA, handler) ?? [])] as unknown[];
        const skipped = (n: string) => (Reflect.getMetadata(`THROTTLER:SKIP${n}`, handler) ?? Reflect.getMetadata(`THROTTLER:SKIP${n}`, cls)) === true;
        out.push({
          route,
          throttles: THROTTLE_NAMES.filter((n) => !skipped(n)),
          guarded: guards.some((g) => g === ThrottlerGuard || (typeof g === 'function' && g.prototype instanceof ThrottlerGuard)),
          perUser: guards.includes(UserThrottlerGuard),
        });
      }
    }
  }
  return out;
}

describe('rate-limit audit: every write route has a named, env-configurable throttle', () => {
  let app: NestExpressApplication;
  let routes: Route[];
  beforeAll(async () => {
    app = await createTestApp({ prisma: { $connect: async () => {}, $disconnect: async () => {} } });
    routes = throttledRoutes(app);
  });
  afterAll(() => app.close());

  const writes = () => routes.filter((r) => WRITE.has(r.route.split(' ')[0]!) || r.route.startsWith('post /auth/'));

  it('finds the same write routes the app registers', () => {
    const registered = registeredRoutes(app)
      .filter((r) => WRITE.has(r.split(' ')[0]!))
      .map((r) => r.replace(` ${API_PREFIX}/`, ' /'));
    expect(writes().map((r) => r.route).sort()).toEqual(expect.arrayContaining(registered.sort()));
    expect(registered.length).toBeGreaterThan(30);
  });

  it('no write route lacks a named throttle (lists offenders)', () => {
    const missing = writes()
      .filter((r) => !EXEMPT[r.route])
      .filter((r) => !r.guarded || r.throttles.length !== 1)
      .map((r) => `${r.route} (guard: ${r.guarded}, throttles: ${r.throttles.join(',') || 'none'})`);
    expect(missing).toEqual([]);
  });

  it('every exemption is a real, unthrottled write route', () => {
    const byRoute = new Map(writes().map((r) => [r.route, r]));
    for (const route of Object.keys(EXEMPT)) {
      const r = byRoute.get(route);
      if (r) expect({ route, guarded: r.guarded && r.throttles.length === 1 }).toEqual({ route, guarded: false });
    }
  });

  it('every named throttle has a *_RATE_LIMIT_PER_MINUTE (or _PER_HOUR) env var with a default', () => {
    const shape = envSchema.shape as Record<string, unknown>;
    for (const n of THROTTLE_NAMES) {
      expect(THROTTLES[n].env).toMatch(/^[A-Z_]+_RATE_LIMIT_PER_(MINUTE|HOUR)$/);
      expect(shape[THROTTLES[n].env]).toBeDefined();
      expect(THROTTLES[n].ttl).toBe(THROTTLES[n].env.endsWith('HOUR') ? 3_600_000 : 60_000);
    }
  });

  it('per-user throttles use the per-user guard; per-IP ones the IP guard', () => {
    const wrong = routes
      .filter((r) => r.guarded && r.throttles.length === 1)
      .filter((r) => THROTTLES[r.throttles[0]!].perIp === r.perUser)
      .map((r) => r.route);
    expect(wrong).toEqual([]);
  });

  it('openapi documents 429 on every throttled route', () => {
    const paths = openapi.paths as Record<string, Record<string, { responses?: Record<string, unknown> }>>;
    const missing = routes
      .filter((r) => r.guarded && r.throttles.length === 1)
      .filter((r) => {
        const [method, path] = r.route.split(' ') as [string, string];
        return !paths[path]?.[method]?.responses?.['429'];
      })
      .map((r) => r.route);
    expect(missing).toEqual([]);
  });

  it('GET progression-suggestions is under the per-user plans throttle', () => {
    expect(routes.find((r) => r.route === 'get /workout-plans/{planId}/progression-suggestions')).toMatchObject({ guarded: true, perUser: true, throttles: ['plans'] });
  });

  it('docs/DEPLOY.md lists every rate-limit env var', () => {
    const deploy = readFileSync(join(__dirname, '..', '..', 'docs', 'DEPLOY.md'), 'utf8');
    expect(THROTTLE_NAMES.map((n) => THROTTLES[n].env).filter((e) => !deploy.includes(`\`${e}\``))).toEqual([]);
  });
});

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
(run ? describe : describe.skip)('a new per-user write throttle (HTTP + Postgres)', () => {
  it('returns 429 RATE_LIMITED with Retry-After past GOALS_RATE_LIMIT_PER_MINUTE, per user', async () => {
    const app = await createTestApp({ env: { GOALS_RATE_LIMIT_PER_MINUTE: '2' } });
    try {
      const http = () => request(app.getHttpServer());
      const signUp = async () =>
        (await http().post(`${API_PREFIX}/auth/register`).send({ email: `rl-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body.accessToken as string;
      const [a, b] = [await signUp(), await signUp()];
      const post = (t: string) => http().post(`${API_PREFIX}/goals`).set('Authorization', `Bearer ${t}`).set('Idempotency-Key', randomUUID()).send({ type: 'lose_weight', startValue: 72, targetValue: 65 });
      await post(a).expect(201);
      await post(a).expect(201);
      const limited = await post(a).expect(429);
      expect(limited.body.code).toBe('RATE_LIMITED');
      expect(limited.headers['retry-after']).toBeDefined();
      await post(b).expect(201);
    } finally {
      await app.close();
    }
  });

  it('GET progression-suggestions returns 429 RATE_LIMITED with Retry-After past PLANS_RATE_LIMIT_PER_MINUTE, per user', async () => {
    const app = await createTestApp({ env: { PLANS_RATE_LIMIT_PER_MINUTE: '2' } });
    try {
      const http = () => request(app.getHttpServer());
      const signUp = async () =>
        (await http().post(`${API_PREFIX}/auth/register`).send({ email: `rl-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body.accessToken as string;
      const [a, b] = [await signUp(), await signUp()];
      const id = randomUUID(); // unknown plan: 404 still counts against the limit
      const get = (t: string) => http().get(`${API_PREFIX}/workout-plans/${id}/progression-suggestions`).set('Authorization', `Bearer ${t}`);
      await get(a).expect(404);
      await get(a).expect(404);
      const limited = await get(a).expect(429);
      expect(limited.body.code).toBe('RATE_LIMITED');
      expect(limited.headers['retry-after']).toBeDefined();
      await get(b).expect(404);
    } finally {
      await app.close();
    }
  });
});
