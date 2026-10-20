/**
 * Auth flows over HTTP against a migrated Postgres (DB_TESTS=1).
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;

describeDb('auth (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const password = 'correct horse battery staple';
  const email = () => `user-${randomUUID()}@example.in`;

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
  });
  afterAll(async () => {
    await app.close();
  });

  const http = () => request(app.getHttpServer());
  function register(address = email(), headers: Record<string, string> = {}) {
    const req = http().post('/api/v1/auth/register');
    for (const [k, v] of Object.entries(headers)) req.set(k, v);
    return req.send({ email: address, password, displayName: 'Asha' });
  }

  it('registers with an argon2id hash, India profile defaults, and a token pair', async () => {
    const address = email();
    const res = await register(address.toUpperCase()).expect(201);
    expect(res.body).toMatchObject({ tokenType: 'Bearer', expiresIn: 900, user: { email: address, displayName: 'Asha' } });
    expect(res.body.user.passwordHash).toBeUndefined();
    const user = await prisma.user.findUniqueOrThrow({ where: { email: address }, include: { profile: true } });
    expect(user.passwordHash.startsWith('$argon2id$')).toBe(true);
    expect(user.passwordHash).not.toContain(password);
    expect(user.profile).toMatchObject({ timezone: 'Asia/Kolkata', currency: 'INR', unitSystem: 'metric' });
    const stored = await prisma.refreshToken.findMany({ where: { userId: user.id } });
    expect(stored).toHaveLength(1);
    expect(stored[0]!.tokenHash).not.toBe(res.body.refreshToken);
  });

  it('returns 409 EMAIL_TAKEN for an existing email', async () => {
    const address = email();
    await register(address).expect(201);
    const res = await register(address).expect(409);
    expect(res.body.code).toBe('EMAIL_TAKEN');
  });

  it('register idempotency: a retry after success is EMAIL_TAKEN, tokens are never stored, the row dies with the account', async () => {
    const address = email();
    const key = randomUUID();
    const first = await register(address, { 'Idempotency-Key': key }).expect(201);
    const retry = await register(address, { 'Idempotency-Key': key }).expect(409);
    expect(retry.body.code).toBe('EMAIL_TAKEN');
    const row = await prisma.idempotencyKey.findUniqueOrThrow({ where: { scope_key: { scope: 'anon:register', key } } });
    expect(row.responseBody).toBeNull();
    expect(row.userId).toBe(first.body.user.id);
    await http().delete('/api/v1/me').set('Authorization', `Bearer ${first.body.accessToken}`).send({ password }).expect(204);
    expect(await prisma.idempotencyKey.count({ where: { key } })).toBe(0);
  });

  it('register idempotency: parallel same-key sign-ups create one account', async () => {
    const address = email();
    const key = randomUUID();
    const results = await Promise.all(Array.from({ length: 5 }, () => register(address, { 'Idempotency-Key': key })));
    const statuses = results.map((r) => r.status);
    expect(statuses.filter((s) => s === 201)).toHaveLength(1);
    for (const r of results.filter((x) => x.status !== 201)) {
      expect(r.status).toBe(409);
      expect(['IDEMPOTENCY_IN_PROGRESS', 'EMAIL_TAKEN']).toContain(r.body.code);
    }
    expect(await prisma.user.count({ where: { email: address } })).toBe(1);
  });

  it('register idempotency: the same key for a different email is IDEMPOTENCY_KEY_REUSED', async () => {
    const key = randomUUID();
    await register(email(), { 'Idempotency-Key': key }).expect(201);
    const res = await register(email(), { 'Idempotency-Key': key }).expect(409);
    expect(res.body.code).toBe('IDEMPOTENCY_KEY_REUSED');
  });

  it('logs in, and answers an unknown email and a wrong password the same way', async () => {
    const address = email();
    await register(address).expect(201);
    const ok = await http().post('/api/v1/auth/login').send({ email: ` ${address.toUpperCase()} `, password }).expect(200);
    expect(ok.body.accessToken).toEqual(expect.any(String));
    const wrong = await http().post('/api/v1/auth/login').send({ email: address, password: 'nope-nope-nope' }).expect(401);
    const unknown = await http().post('/api/v1/auth/login').send({ email: email(), password }).expect(401);
    expect(wrong.body.code).toBe('INVALID_CREDENTIALS');
    expect(unknown.body.code).toBe('INVALID_CREDENTIALS');
    expect(wrong.body.message).toBe(unknown.body.message);
  });

  it('rotates refresh tokens; reusing an old one revokes the whole family', async () => {
    const first = (await register()).body;
    const second = (await http().post('/api/v1/auth/refresh').send({ refreshToken: first.refreshToken }).expect(200)).body;
    expect(second.refreshToken).not.toBe(first.refreshToken);
    const third = (await http().post('/api/v1/auth/refresh').send({ refreshToken: second.refreshToken }).expect(200)).body;

    const reuse = await http().post('/api/v1/auth/refresh').send({ refreshToken: first.refreshToken }).expect(401);
    expect(reuse.body.code).toBe('REFRESH_TOKEN_REUSED');
    // The newest token in the family is now revoked too.
    const after = await http().post('/api/v1/auth/refresh').send({ refreshToken: third.refreshToken }).expect(401);
    expect(after.body.code).toBe('REFRESH_TOKEN_REUSED');
    const live = await prisma.refreshToken.count({ where: { userId: first.user.id, revokedAt: null } });
    expect(live).toBe(0);
  });

  it('lets only one of two concurrent refreshes with the same token win', async () => {
    const first = (await register()).body;
    const results = await Promise.all([
      http().post('/api/v1/auth/refresh').send({ refreshToken: first.refreshToken }),
      http().post('/api/v1/auth/refresh').send({ refreshToken: first.refreshToken }),
    ]);
    expect(results.map((r) => r.status).sort()).toEqual([200, 401]);
  });

  it('rejects unknown and expired refresh tokens', async () => {
    const unknown = await http().post('/api/v1/auth/refresh').send({ refreshToken: 'definitely-not-a-token' }).expect(401);
    expect(unknown.body.code).toBe('REFRESH_TOKEN_INVALID');
    const first = (await register()).body;
    await prisma.refreshToken.updateMany({ where: { userId: first.user.id }, data: { expiresAt: new Date(Date.now() - 1000) } });
    const expired = await http().post('/api/v1/auth/refresh').send({ refreshToken: first.refreshToken }).expect(401);
    expect(expired.body.code).toBe('REFRESH_TOKEN_EXPIRED');
  });

  it('does not accept a refresh token as a bearer token', async () => {
    const first = (await register()).body;
    await http().post('/api/v1/auth/logout').set('Authorization', `Bearer ${first.refreshToken}`).send({ refreshToken: first.refreshToken }).expect(401);
  });

  it('logs out by revoking the refresh token, and only the caller’s own token', async () => {
    const a = (await register()).body;
    const b = (await register()).body;
    await http().post('/api/v1/auth/logout').set('Authorization', `Bearer ${a.accessToken}`).send({ refreshToken: b.refreshToken }).expect(204);
    await http().post('/api/v1/auth/refresh').send({ refreshToken: b.refreshToken }).expect(200);
    await http().post('/api/v1/auth/logout').set('Authorization', `Bearer ${a.accessToken}`).send({ refreshToken: a.refreshToken }).expect(204);
    const res = await http().post('/api/v1/auth/refresh').send({ refreshToken: a.refreshToken }).expect(401);
    expect(res.body.code).toBe('REFRESH_TOKEN_REUSED');
  });

  it('deletes the account only with the right password, then the old credentials stop working', async () => {
    const address = email();
    const me = (await register(address)).body;
    const wrong = await http().delete('/api/v1/me').set('Authorization', `Bearer ${me.accessToken}`).send({ password: 'wrong-password' }).expect(403);
    expect(wrong.body.code).toBe('INVALID_PASSWORD');
    await http().delete('/api/v1/me').set('Authorization', `Bearer ${me.accessToken}`).send({ password }).expect(204);
    expect(await prisma.refreshToken.count({ where: { userId: me.user.id } })).toBe(0);
    await http().post('/api/v1/auth/login').send({ email: address, password }).expect(401);
    await http().post('/api/v1/auth/refresh').send({ refreshToken: me.refreshToken }).expect(401);
  });

  it('rate limits auth routes with 429 RATE_LIMITED and Retry-After', async () => {
    const limited = await createTestApp({ env: { AUTH_RATE_LIMIT_PER_MINUTE: '3' } });
    try {
      const statuses: number[] = [];
      let last: request.Response | undefined;
      for (let i = 0; i < 4; i += 1) {
        last = await request(limited.getHttpServer()).post('/api/v1/auth/login').send({ email: email(), password });
        statuses.push(last.status);
      }
      expect(statuses).toEqual([401, 401, 401, 429]);
      expect(last!.body.code).toBe('RATE_LIMITED');
      expect(Number(last!.headers['retry-after'])).toBeGreaterThan(0);
    } finally {
      await limited.close();
    }
  });
});
