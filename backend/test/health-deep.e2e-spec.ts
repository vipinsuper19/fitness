/**
 * GET /health/deep (docs/specs/health-deep.md). Stub database by default; the last block uses the real one (DB_TESTS=1).
 * Also guards that plain GET /health is unchanged.
 */
import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { createTestApp } from './helpers';

const CONN = 'postgresql://svc_user:Sup3rS3cretPw@db.abcdefghij.supabase.co:6543/postgres?pgbouncer=true';
type Mode = 'ok' | 'dbThrows' | 'dbHangs' | 'migrationThrows' | 'noMigrations';
let mode: Mode = 'ok';

const stubPrisma = {
  $connect: async () => {},
  $disconnect: async () => {},
  $queryRaw: (strings: TemplateStringsArray) => {
    const sql = strings.join('?');
    const fail = () => Promise.reject(new Error(`connect ECONNREFUSED to ${CONN} (PostgreSQL 15.4 on db.abcdefghij.supabase.co)\n    at Socket.<anonymous> (node_modules/pg/lib/client.js:1:1)`));
    if (mode === 'dbThrows') return fail();
    if (mode === 'dbHangs') return new Promise(() => {});
    if (sql.includes('_prisma_migrations')) {
      if (mode === 'migrationThrows') return fail();
      return Promise.resolve(mode === 'noMigrations' ? [] : [{ migration_name: '20261020000000_add_things' }]);
    }
    return Promise.resolve([{ '?column?': 1 }]);
  },
};

const LEAKS = [CONN, 'svc_user', 'Sup3rS3cretPw', 'supabase.co', 'postgresql://', 'ECONNREFUSED', 'PostgreSQL', '15.4', 'node_modules', ' at ', 'stack', 'message', 'a'.repeat(32), 'b'.repeat(32)];
const expectNoLeak = (res: request.Response) => {
  for (const s of LEAKS) expect(res.text).not.toContain(s);
  expect(Object.keys(res.body).sort()).toEqual(['db', 'migration', 'status']);
};

describe('GET /health/deep (stub database)', () => {
  let app: NestExpressApplication;
  const http = () => request(app.getHttpServer());
  beforeAll(async () => {
    app = await createTestApp({ prisma: stubPrisma, env: { DATABASE_URL: CONN } });
  });
  afterAll(() => app.close());
  beforeEach(() => {
    mode = 'ok';
  });

  it('ok: db up and the latest migration id', async () => {
    const res = await http().get('/health/deep').expect(200);
    expect(res.body).toEqual({ status: 'ok', db: 'up', migration: '20261020000000_add_things' });
    expectNoLeak(res);
  });

  it('db failure → 503 degraded/down, no error detail, connection string or secret in the body', async () => {
    mode = 'dbThrows';
    const res = await http().get('/health/deep').expect(503);
    expect(res.body).toEqual({ status: 'degraded', db: 'down', migration: null });
    expectNoLeak(res);
  });

  it('db timeout (~2 s) → 503', async () => {
    mode = 'dbHangs';
    const started = process.hrtime.bigint();
    const res = await http().get('/health/deep').expect(503);
    expect(Number(process.hrtime.bigint() - started) / 1e6).toBeLessThan(4000);
    expect(res.body).toEqual({ status: 'degraded', db: 'down', migration: null });
  }, 10_000);

  it.each<Mode>(['migrationThrows', 'noMigrations'])('%s → 200 degraded, db up, migration null', async (m) => {
    mode = m;
    const res = await http().get('/health/deep').expect(200);
    expect(res.body).toEqual({ status: 'degraded', db: 'up', migration: null });
    expectNoLeak(res);
  });

  it('plain GET /health is unchanged', async () => {
    const res = await http().get('/health').expect(200);
    expect(Object.keys(res.body).sort()).toEqual(['status', 'uptimeSeconds']);
    await http().get('/api/v1/health/deep').expect(404);
  });
});

describe('GET /health/deep rate limit', () => {
  it('429 RATE_LIMITED with Retry-After past HEALTH_DEEP_RATE_LIMIT_PER_MINUTE (per IP)', async () => {
    const app = await createTestApp({ prisma: stubPrisma, env: { HEALTH_DEEP_RATE_LIMIT_PER_MINUTE: '2' } });
    try {
      const http = () => request(app.getHttpServer());
      await http().get('/health/deep').expect(200);
      await http().get('/health/deep').expect(200);
      const limited = await http().get('/health/deep').expect(429);
      expect(limited.body.code).toBe('RATE_LIMITED');
      expect(limited.headers['retry-after']).toMatch(/^[1-9][0-9]*$/);
      await http().get('/health').expect(200); // plain /health is not throttled
    } finally {
      await app.close();
    }
  });
});

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
(run ? describe : describe.skip)('GET /health/deep (real database)', () => {
  it('reports the latest applied migration and leaks nothing from DATABASE_URL', async () => {
    const app = await createTestApp();
    try {
      const res = await request(app.getHttpServer()).get('/health/deep').expect(200);
      expect(res.body).toEqual({ status: 'ok', db: 'up', migration: expect.stringMatching(/^\d{14}_[a-z0-9_]+$/) });
      const url = new URL(process.env.DATABASE_URL!);
      for (const s of [process.env.DATABASE_URL!, url.password, url.hostname + ':' + url.port].filter((v) => v.length > 3)) expect(res.text).not.toContain(s);
    } finally {
      await app.close();
    }
  });
});
