/**
 * Request id + structured request log (docs/specs/request-log.md), DB_TESTS=1: the X-Request-Id header is echoed or
 * generated, unsafe ids are replaced, error bodies carry requestId, and the log line holds the route template and
 * nothing personal (no email, password, token, body, query string or IP) on register and login.
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { routeTemplate } from '../src/common/request-log';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PASSWORD = 'correct horse battery';

describe('routeTemplate', () => {
  it('turns express params into {param} and returns null when nothing matched', () => {
    expect(routeTemplate({ baseUrl: '', route: { path: '/api/v1/goals/:goalId' } })).toBe('/api/v1/goals/{goalId}');
    expect(routeTemplate({})).toBeNull();
  });
});

describeDb('request id and request log', () => {
  let app: NestExpressApplication;
  let lines: string[];
  let spy: jest.SpyInstance;
  const http = () => request(app.getHttpServer());
  const logged = () => lines.flatMap((l) => l.split('\n')).filter((l) => l.startsWith('{"type":"request"')).map((l) => JSON.parse(l) as Record<string, unknown>);

  beforeAll(async () => {
    app = await createTestApp({ env: { REQUEST_LOG: 'on' } });
  });
  beforeEach(() => {
    lines = [];
    spy = jest.spyOn(process.stdout, 'write').mockImplementation(((chunk: unknown) => {
      lines.push(String(chunk));
      return true;
    }) as typeof process.stdout.write);
  });
  afterEach(() => spy.mockRestore());
  afterAll(async () => {
    await app.close();
  });

  it('echoes a safe incoming X-Request-Id and generates a UUID when none is sent', async () => {
    const id = randomUUID();
    expect((await http().get('/health').set('X-Request-Id', id).expect(200)).headers['x-request-id']).toBe(id);
    expect((await http().get('/health').set('X-Request-Id', 'abc-DEF-12345').expect(200)).headers['x-request-id']).toBe('abc-DEF-12345');
    expect((await http().get('/health').expect(200)).headers['x-request-id']).toMatch(UUID);
  });

  it.each([
    ['log injection', 'abcdefgh\\n{"type":"request","userId":"x"}'],
    ['quotes and spaces', 'abc" OR 1=1 --'],
    ['too short', 'abc'],
    ['too long', 'a'.repeat(65)],
    ['html', '<script>alert(1)</script>'],
  ])('replaces a malicious incoming id (%s) with a generated UUID', async (_label, bad) => {
    const res = await http().get('/health').set('X-Request-Id', bad).expect(200);
    expect(res.headers['x-request-id']).toMatch(UUID);
    expect(lines.join('')).not.toContain(bad);
  });

  it('every error body carries the same requestId as the header, and the log line matches', async () => {
    const res = await http().get('/api/v1/goals').expect(401);
    expect(res.body.requestId).toBe(res.headers['x-request-id']);
    const nf = await http().get(`/api/v1/no-such-route/${randomUUID()}?secret=1`).expect(404);
    expect(nf.body.requestId).toBe(nf.headers['x-request-id']);
    const [a, b] = logged();
    expect(a).toEqual(expect.objectContaining({ requestId: res.headers['x-request-id'], method: 'GET', route: '/api/v1/goals', status: 401 }));
    expect(a).not.toHaveProperty('userId');
    // Unmatched paths never log the raw path or query.
    expect(b).toEqual(expect.objectContaining({ requestId: nf.headers['x-request-id'], route: null, status: 404 }));
    expect(lines.join('')).not.toContain('no-such-route');
    expect(lines.join('')).not.toContain('secret');
  });

  it('register and login log no email, password, token, body, query, IP or user agent; authed requests log the route template and userId', async () => {
    const email = `log-${randomUUID()}@example.in`;
    const reg = await http().post('/api/v1/auth/register?utm=campaign-x').set('User-Agent', 'UA-marker-123').send({ email, password: PASSWORD }).expect(201);
    const login = await http().post('/api/v1/auth/login').send({ email, password: PASSWORD }).expect(200);
    const bad = await http().post('/api/v1/auth/login').send({ email, password: 'wrong password!!' }).expect(401);
    const goal = await http().get(`/api/v1/goals/${randomUUID()}`).set('Authorization', `Bearer ${login.body.accessToken}`);
    const text = lines.join('');
    for (const secret of [email, PASSWORD, 'wrong password!!', reg.body.accessToken, reg.body.refreshToken, login.body.accessToken, login.body.refreshToken, 'campaign-x', 'UA-marker-123', '127.0.0.1', '::ffff', 'Bearer']) {
      expect(text).not.toContain(secret);
    }
    const entries = logged();
    expect(entries.map((e) => [e.route, e.status])).toEqual([
      ['/api/v1/auth/register', 201],
      ['/api/v1/auth/login', 200],
      ['/api/v1/auth/login', 401],
      ['/api/v1/goals/{goalId}', goal.status],
    ]);
    expect(entries[2]!.requestId).toBe(bad.headers['x-request-id']);
    expect(entries[3]!.userId).toBe(reg.body.user.id);
    for (const e of entries) {
      expect(Object.keys(e).sort()).toEqual(expect.arrayContaining(['durationMs', 'method', 'requestId', 'route', 'status', 'type']));
      expect(Object.keys(e).every((k) => ['type', 'requestId', 'method', 'route', 'status', 'durationMs', 'userId'].includes(k))).toBe(true);
      expect(typeof e.durationMs).toBe('number');
    }
  });
});
