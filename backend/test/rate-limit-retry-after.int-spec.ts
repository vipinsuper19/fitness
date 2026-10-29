/**
 * Every 429 carries Retry-After (DB_TESTS=1): for each named throttle (src/common/rate-limit.ts), set its env limit
 * to 1, hit one representative route twice, and expect a 429 RATE_LIMITED with `Retry-After` = positive integer seconds.
 * Throttle guards run before validation, so request bodies don't matter (the first call may be a 400/401/404).
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { THROTTLES, THROTTLE_NAMES, type ThrottleName } from '../src/common/rate-limit';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const PASSWORD = 'correct horse battery';

/** One representative route per throttle. Per-user throttles are called with a signed-in user's token. */
const ROUTES: Record<ThrottleName, { method: 'get' | 'post' | 'put' | 'patch'; path: string; body?: object }> = {
  auth: { method: 'post', path: '/api/v1/auth/login', body: { email: 'nobody@example.invalid', password: 'x' } },
  refresh: { method: 'post', path: '/api/v1/auth/refresh', body: { refreshToken: 'not-a-token' } },
  export: { method: 'get', path: '/api/v1/me/export' },
  targetHistory: { method: 'get', path: '/api/v1/me/target-history' },
  notificationPrefs: { method: 'get', path: '/api/v1/me/notification-preferences' },
  profile: { method: 'put', path: '/api/v1/me/profile', body: {} },
  goals: { method: 'post', path: '/api/v1/goals', body: {} },
  plans: { method: 'post', path: '/api/v1/workout-plans', body: {} },
  sessions: { method: 'post', path: '/api/v1/workout-sessions', body: {} },
  nutrition: { method: 'post', path: '/api/v1/foods', body: {} },
  bodyMeasurements: { method: 'post', path: '/api/v1/body-measurements', body: {} },
  challenges: { method: 'post', path: '/api/v1/challenges', body: {} },
  healthDeep: { method: 'get', path: '/health/deep' },
};

function expectRateLimited(res: request.Response) {
  expect(res.status).toBe(429);
  expect(res.headers['retry-after']).toMatch(/^[1-9][0-9]*$/);
  expect(res.body).toEqual(expect.objectContaining({ code: 'RATE_LIMITED', requestId: expect.any(String) }));
}

describeDb('every 429 carries Retry-After', () => {
  it('covers every named throttle', () => {
    expect(Object.keys(ROUTES).sort()).toEqual([...THROTTLE_NAMES].sort());
  });

  it.each(THROTTLE_NAMES)('%s: limit 1 → second call is 429 with Retry-After', async (name) => {
    const app: NestExpressApplication = await createTestApp({ env: { [THROTTLES[name].env]: '1' } });
    try {
      const http = () => request(app.getHttpServer());
      let token: string | undefined;
      if (!THROTTLES[name].perIp) {
        const reg = await http().post('/api/v1/auth/register').send({ email: `ra-${randomUUID()}@example.in`, password: PASSWORD }).expect(201);
        token = reg.body.accessToken as string;
      }
      const { method, path, body } = ROUTES[name];
      const call = () => {
        const r = http()[method](path).set('Idempotency-Key', randomUUID());
        if (token) r.set('Authorization', `Bearer ${token}`);
        return body ? r.send(body) : r;
      };
      const first = await call();
      expect(first.status).not.toBe(429);
      const second = await call();
      expectRateLimited(second);
      // The longest window is the hour-long export limit; nothing should exceed its TTL.
      expect(Number(second.headers['retry-after'])).toBeLessThanOrEqual(THROTTLES[name].ttl / 1000);
    } finally {
      await app.close();
    }
  });

  it('login failure limiter (LOGIN_MAX_FAILURES_PER_ACCOUNT=1) also sets Retry-After', async () => {
    const app: NestExpressApplication = await createTestApp({ env: { LOGIN_MAX_FAILURES_PER_ACCOUNT: '1' } });
    try {
      const http = () => request(app.getHttpServer());
      const email = `rl-${randomUUID()}@example.in`;
      await http().post('/api/v1/auth/register').send({ email, password: PASSWORD }).expect(201);
      let res = await http().post('/api/v1/auth/login').send({ email, password: 'wrong password!!' });
      for (let i = 0; res.status !== 429 && i < 3; i += 1) res = await http().post('/api/v1/auth/login').send({ email, password: 'wrong password!!' });
      expectRateLimited(res);
    } finally {
      await app.close();
    }
  });
});
