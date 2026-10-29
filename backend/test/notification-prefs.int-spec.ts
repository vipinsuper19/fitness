/**
 * Notification preferences (docs/specs/notification-preferences.md) over HTTP against Postgres
 * (DB_TESTS=1): defaults without a row, PATCH merge and validation, Idempotency-Key, 429,
 * export and account deletion, checked against contracts/openapi.yaml. Storage only.
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import type { NestExpressApplication } from '@nestjs/platform-express';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import request from 'supertest';
import { parse } from 'yaml';

import { DEFAULT_TYPES } from '../src/notification-prefs/notification-prefs.rules';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const NOW = new Date('2026-10-29T06:30:00Z');
const DATE_ONLY: Parameters<typeof jest.useFakeTimers>[0] = {
  now: NOW,
  doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'],
};
const PASSWORD = 'correct horse battery';
const URL = '/api/v1/me/notification-preferences';

const spec = parse(readFileSync(join(__dirname, '..', '..', 'contracts', 'openapi.yaml'), 'utf8')) as { components: unknown };
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: 'openapi', components: spec.components });
const schema = (name: string) => ajv.compile({ $ref: `openapi#/components/schemas/${name}` });
const validPrefs = schema('NotificationPreferences');
const validUpdate = schema('NotificationPreferencesUpdate');
const validExport = schema('DataExport');
const valid = (v: ReturnType<typeof schema>, body: unknown) => {
  const ok = v(body);
  expect(v.errors ?? null).toBeNull();
  expect(ok).toBe(true);
};
const DEFAULT_VIEW = { enabled: true, types: DEFAULT_TYPES, quietHours: { enabled: true, start: '22:00', end: '07:00' }, timezone: 'Asia/Kolkata', updatedAt: null };

describeDb('notification preferences (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const signUp = async () => {
    const b = (await http().post('/api/v1/auth/register').send({ email: `np-${randomUUID()}@example.in`, password: PASSWORD }).expect(201)).body;
    return { token: b.accessToken as string, id: b.user.id as string };
  };
  const get = async (token: string) => {
    const b = (await http().get(URL).set('Authorization', `Bearer ${token}`).expect(200)).body;
    valid(validPrefs, b);
    return b;
  };
  const patch = (token: string, body: unknown, key?: string) => {
    const r = http().patch(URL).set('Authorization', `Bearer ${token}`);
    return (key ? r.set('Idempotency-Key', key) : r).send(body as object);
  };
  const rowOf = (userId: string) => prisma.notificationPreference.findUnique({ where: { userId } });

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
  });
  beforeEach(() => jest.useFakeTimers(DATE_ONLY));
  afterEach(() => jest.useRealTimers());
  afterAll(async () => {
    await app.close();
  });

  it('GET with no row returns the defaults and writes nothing; a new user has no row', async () => {
    const u = await signUp();
    expect(await rowOf(u.id)).toBeNull();
    expect(await get(u.token)).toEqual(DEFAULT_VIEW);
    expect(await get(u.token)).toEqual(DEFAULT_VIEW);
    expect(await rowOf(u.id)).toBeNull();
  });

  it('echoes the profile timezone read-only', async () => {
    const u = await signUp();
    await http().put('/api/v1/me/profile').set('Authorization', `Bearer ${u.token}`).send({ timezone: 'America/New_York' }).expect(200);
    expect((await get(u.token)).timezone).toBe('America/New_York');
  });

  it('PATCH merges partially: a single type, the master switch, quietHours.start only', async () => {
    const u = await signUp();
    const a = (await patch(u.token, { types: { water_reminder: true } }).expect(200)).body;
    valid(validPrefs, a);
    expect(a).toMatchObject({ enabled: true, types: { ...DEFAULT_TYPES, water_reminder: true }, quietHours: DEFAULT_VIEW.quietHours });
    expect(a.updatedAt).toBe(NOW.toISOString());
    const b = (await patch(u.token, { enabled: false }).expect(200)).body;
    expect(b).toMatchObject({ enabled: false, types: { ...DEFAULT_TYPES, water_reminder: true } });
    const c = (await patch(u.token, { quietHours: { start: '23:30' } }).expect(200)).body;
    expect(c).toMatchObject({ enabled: false, types: { ...DEFAULT_TYPES, water_reminder: true }, quietHours: { enabled: true, start: '23:30', end: '07:00' } });
    expect(await get(u.token)).toEqual(c);
    const row = await rowOf(u.id);
    expect(row!.types).toEqual({ water_reminder: true }); // only the types the user set
  });

  it('a no-op PATCH returns 200 and keeps updatedAt; {} without a row creates nothing', async () => {
    const u = await signUp();
    expect((await patch(u.token, {}).expect(200)).body).toEqual(DEFAULT_VIEW);
    expect((await patch(u.token, { types: { badge_earned: true } }).expect(200)).body).toEqual(DEFAULT_VIEW);
    expect(await rowOf(u.id)).toBeNull();
    const first = (await patch(u.token, { types: { food_log_reminder: true } }).expect(200)).body;
    jest.setSystemTime(new Date(NOW.getTime() + 60_000));
    const again = (await patch(u.token, { types: { food_log_reminder: true }, quietHours: { enabled: true } }).expect(200)).body;
    expect(again.updatedAt).toBe(first.updatedAt);
    const changed = (await patch(u.token, { quietHours: { enabled: false } }).expect(200)).body;
    expect(changed.updatedAt).toBe(new Date(NOW.getTime() + 60_000).toISOString());
  });

  it('the response lists every type; a stored flag for a removed type is not returned and is dropped on write', async () => {
    const u = await signUp();
    await prisma.notificationPreference.create({
      data: { userId: u.id, enabled: true, types: { sms_digest: true, water_reminder: true }, quietEnabled: true, quietStart: new Date('1970-01-01T22:00:00Z'), quietEnd: new Date('1970-01-01T07:00:00Z') },
    });
    const view = await get(u.token);
    expect(view.types).toEqual({ ...DEFAULT_TYPES, water_reminder: true });
    expect(Object.keys(view.types)).toEqual(Object.keys(DEFAULT_TYPES));
    await patch(u.token, { types: { badge_earned: false } }).expect(200);
    expect((await rowOf(u.id))!.types).toEqual({ water_reminder: true, badge_earned: false });
    // A type added later (not stored) shows its default: every type the user hasn't set reads its default.
    expect((await get(u.token)).types.streak_reminder).toBe(DEFAULT_TYPES.streak_reminder);
  });

  it.each([
    [{ types: { foo: true } }, 'types.foo'],
    [{ types: { sms_digest: true } }, 'types.sms_digest'],
    [{ enabled: 'true' }, 'enabled'],
    [{ types: { badge_earned: 0 } }, 'types.badge_earned'],
    [{ enabled: null }, 'enabled'],
    [{ quietHours: null }, 'quietHours'],
    [{ quietHours: { start: '24:00' } }, 'quietHours.start'],
    [{ quietHours: { start: '7:00' } }, 'quietHours.start'],
    [{ quietHours: { end: '07:60' } }, 'quietHours.end'],
    [{ quietHours: { start: '10:00', end: '10:00' } }, 'quietHours'],
    [{ quietHours: { start: '07:00' } }, 'quietHours'], // equals the stored end after the merge
    [{ nope: 1 }, 'nope'],
    [{ timezone: 'UTC' }, 'timezone'],
    [{ updatedAt: '2026-10-29T00:00:00Z' }, 'updatedAt'],
  ])('400 VALIDATION_FAILED for %j (field %s); nothing is saved', async (body, field) => {
    const u = await signUp();
    await patch(u.token, { types: { water_reminder: true } }).expect(200);
    const before = await rowOf(u.id);
    const r = await patch(u.token, { enabled: false, ...body }).expect(400);
    expect(r.body.code).toBe('VALIDATION_FAILED');
    expect(r.body.details.fields).toHaveProperty([field]);
    expect(await rowOf(u.id)).toEqual(before);
  });

  it('request bodies that pass are valid NotificationPreferencesUpdate; rejected unknown types are not', () => {
    valid(validUpdate, { enabled: false, types: { water_reminder: true }, quietHours: { start: '23:30' } });
    expect(validUpdate({ types: { foo: true } })).toBe(false);
  });

  it('two concurrent PATCHes for different types both persist (per-user advisory lock)', async () => {
    const u = await signUp();
    await patch(u.token, { types: { water_reminder: true } }).expect(200); // an existing row: both requests read-merge-write it
    const lockKey = `notification-prefs:${u.id}`;
    const waiters = async () =>
      Number((await prisma.$queryRaw<Array<{ n: bigint }>>`
        SELECT count(*) AS n FROM pg_locks
        WHERE locktype = 'advisory' AND NOT granted AND objsubid = 1
          AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(${lockKey}, 0)`)[0]!.n);
    // Hold the user's lock so both PATCHes are queued behind it before either reads the row;
    // then release it. Deterministic: the test waits until both are blocked on the lock.
    const results = await prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT pg_advisory_xact_lock(hashtextextended(${lockKey}, 0))::text`;
      const a = patch(u.token, { types: { food_log_reminder: true } }).then((r) => r);
      const b = patch(u.token, { types: { badge_earned: false } }).then((r) => r);
      // Date is faked, so bound the wait by attempts (500 x 20 ms = 10 s), not by the clock.
      for (let i = 0; (await waiters()) < 2; i += 1) {
        if (i >= 500) throw new Error('both PATCHes never reached the lock');
        await new Promise((r) => setTimeout(r, 20));
      }
      return [a, b] as const;
    }, { timeout: 20_000 });
    const [ra, rb] = await Promise.all(results);
    expect([ra.status, rb.status]).toEqual([200, 200]);
    expect((await rowOf(u.id))!.types).toEqual({ water_reminder: true, food_log_reminder: true, badge_earned: false });
    expect((await get(u.token)).types).toEqual({ ...DEFAULT_TYPES, water_reminder: true, food_log_reminder: true, badge_earned: false });
  });

  it('Idempotency-Key: replay returns the stored response; reuse with a different body is 409', async () => {
    const u = await signUp();
    const key = randomUUID();
    const a = await patch(u.token, { types: { water_reminder: true } }, key).expect(200);
    const b = await patch(u.token, { types: { water_reminder: true } }, key).expect(200);
    expect(b.headers['idempotent-replayed']).toBe('true');
    expect(b.body).toEqual(a.body);
    expect((await patch(u.token, { types: { water_reminder: false } }, key).expect(409)).body.code).toBe('IDEMPOTENCY_KEY_REUSED');
  });

  it('requires a signed-in user', async () => {
    await http().get(URL).expect(401);
    await http().patch(URL).send({}).expect(401);
  });

  it('is rate-limited per user and per route with 429', async () => {
    const limited = await createTestApp({ env: { NOTIFICATION_PREFS_RATE_LIMIT_PER_MINUTE: '2' } });
    try {
      const reg = (await request(limited.getHttpServer()).post('/api/v1/auth/register').send({ email: `np-${randomUUID()}@example.in`, password: PASSWORD }).expect(201)).body;
      const auth = `Bearer ${reg.accessToken}`;
      const g = () => request(limited.getHttpServer()).get(URL).set('Authorization', auth);
      const p = () => request(limited.getHttpServer()).patch(URL).set('Authorization', auth).send({});
      await g().expect(200);
      await g().expect(200);
      expect((await g().expect(429)).body.code).toBe('RATE_LIMITED');
      await p().expect(200);
      await p().expect(200);
      expect((await p().expect(429)).body.code).toBe('RATE_LIMITED');
      // Another user is unaffected.
      const other = (await request(limited.getHttpServer()).post('/api/v1/auth/register').send({ email: `np-${randomUUID()}@example.in`, password: PASSWORD }).expect(201)).body;
      await request(limited.getHttpServer()).get(URL).set('Authorization', `Bearer ${other.accessToken}`).expect(200);
    } finally {
      await limited.close();
    }
  });

  it('export includes notificationPreferences (defaults included) and validates; deleting the account removes the row', async () => {
    const u = await signUp();
    let ex = (await http().get('/api/v1/me/export').set('Authorization', `Bearer ${u.token}`).expect(200)).body;
    valid(validExport, ex);
    expect(ex.notificationPreferences).toEqual(DEFAULT_VIEW);
    const saved = (await patch(u.token, { types: { water_reminder: true } }).expect(200)).body;
    ex = (await http().get('/api/v1/me/export').set('Authorization', `Bearer ${u.token}`).expect(200)).body;
    valid(validExport, ex);
    expect(ex.notificationPreferences).toEqual(saved);
    await http().delete('/api/v1/me').set('Authorization', `Bearer ${u.token}`).send({ password: PASSWORD }).expect(204);
    expect(await rowOf(u.id)).toBeNull();
  });
});
