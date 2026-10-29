/**
 * Auth flows over HTTP against a migrated Postgres (DB_TESTS=1).
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { hashRefreshToken, TokenService } from '../src/auth/token.service';
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
    // Claim, issue and link happened together: the old row points at its successor.
    const rows = await prisma.refreshToken.findMany({ where: { userId: first.user.id }, orderBy: { createdAt: 'asc' } });
    expect(rows).toHaveLength(2);
    expect(rows[0]!.revokedAt).not.toBeNull();
    expect(rows[0]!.replacedById).toBe(rows[1]!.id);
    expect(rows[1]!.familyId).toBe(rows[0]!.familyId);
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

  describe('logout revokes the whole refresh family', () => {
    const refresh = (t: string) => http().post('/api/v1/auth/refresh').send({ refreshToken: t });
    const logout = (access: string, t: string) => http().post('/api/v1/auth/logout').set('Authorization', `Bearer ${access}`).send({ refreshToken: t });
    /** One login, rotated twice: [original, middle, current]. */
    async function rotatedFamily(address: string) {
      const login = (await http().post('/api/v1/auth/login').send({ email: address, password }).expect(200)).body;
      const r1 = (await refresh(login.refreshToken).expect(200)).body;
      const r2 = (await refresh(r1.refreshToken).expect(200)).body;
      return { access: r2.accessToken as string, tokens: [login.refreshToken, r1.refreshToken, r2.refreshToken] as string[] };
    }
    const familyOf = async (t: string) => (await prisma.refreshToken.findUniqueOrThrow({ where: { tokenHash: hashRefreshToken(t) } })).familyId;

    it.each([
      ['the current token', 2],
      ['a rotated old token', 0],
    ])('logout with %s revokes every token in that family; another family of the same user still refreshes', async (_label, which) => {
      const address = email();
      const me = (await register(address)).body;
      const other = await rotatedFamily(address);
      const fam = await rotatedFamily(address);
      const familyId = await familyOf(fam.tokens[0]!);

      await logout(fam.access, fam.tokens[which]!).expect(204);

      expect(await prisma.refreshToken.count({ where: { familyId } })).toBe(3);
      expect(await prisma.refreshToken.count({ where: { familyId, revokedAt: null } })).toBe(0);
      for (const t of fam.tokens) expect((await refresh(t).expect(401)).body.code).toBe('REFRESH_TOKEN_REUSED');
      // Other logins of the same user are untouched.
      await refresh(other.tokens[2]!).expect(200);
      await refresh(me.refreshToken).expect(200);
    });

    it('is idempotent: logging out again, or with an unknown token, is 204 and changes nothing else', async () => {
      const address = email();
      const me = (await register(address)).body;
      const fam = await rotatedFamily(address);
      await logout(fam.access, fam.tokens[2]!).expect(204);
      await logout(fam.access, fam.tokens[2]!).expect(204);
      await logout(fam.access, fam.tokens[0]!).expect(204);
      await logout(fam.access, 'definitely-not-a-token').expect(204);
      expect(await prisma.refreshToken.count({ where: { userId: me.user.id, revokedAt: null } })).toBe(1);
      await refresh(me.refreshToken).expect(200);
    });

    // Race: rotate inserts a successor; logout's family UPDATE must not miss it. Both take the family lock
    // (pg_advisory_xact_lock(hashtext(family_id))). The test holds that lock, queues both requests behind it in a
    // chosen order (advisory-lock waiters are granted in queue order), then releases. Deterministic: it waits on
    // pg_locks until each request is blocked, bounded by attempts (Date may be faked), never by the clock.
    it.each([
      ['logout queued first', 'logout'],
      ['refresh queued first', 'refresh'],
    ] as const)('a logout racing a refresh leaves no live token in the family (%s)', async (_label, first) => {
      const address = email();
      await register(address);
      const fam = await rotatedFamily(address);
      const current = fam.tokens[2]!;
      const familyId = await familyOf(current);
      const waiters = async () =>
        Number((await prisma.$queryRaw<Array<{ n: bigint }>>`
          SELECT count(*) AS n FROM pg_locks
          WHERE locktype = 'advisory' AND NOT granted AND objsubid = 1
            AND ((classid::bigint << 32) | objid::bigint) = hashtext(${familyId})::bigint`)[0]!.n);
      const waitFor = async (n: number) => {
        for (let i = 0; (await waiters()) < n; i += 1) {
          if (i >= 500) throw new Error(`only ${await waiters()} of ${n} requests reached the family lock`);
          await new Promise((r) => setTimeout(r, 20));
        }
      };
      const fire = (which: 'logout' | 'refresh') =>
        (which === 'logout' ? logout(fam.access, current) : refresh(current)).then((r) => r);
      const pending = await prisma.$transaction(async (tx) => {
        await tx.$queryRaw`SELECT pg_advisory_xact_lock(hashtext(${familyId}))::text`;
        const a = fire(first);
        await waitFor(1);
        const b = fire(first === 'logout' ? 'refresh' : 'logout');
        await waitFor(2);
        return first === 'logout' ? { out: a, ref: b } : { out: b, ref: a };
      }, { timeout: 20_000 });
      const [out, ref] = await Promise.all([pending.out, pending.ref]);

      expect(out.status).toBe(204);
      expect(await prisma.refreshToken.count({ where: { familyId, revokedAt: null } })).toBe(0);
      if (first === 'logout') {
        expect(ref.status).toBe(401);
        expect(ref.body.code).toBe('REFRESH_TOKEN_REUSED');
      } else {
        // The refresh won the lock: it rotated, then the logout revoked the successor too.
        expect(ref.status).toBe(200);
        const successor = await prisma.refreshToken.findUniqueOrThrow({ where: { tokenHash: hashRefreshToken(ref.body.refreshToken) } });
        expect(successor.familyId).toBe(familyId);
        expect(successor.revokedAt).not.toBeNull();
        expect((await refresh(ref.body.refreshToken).expect(401)).body.code).toBe('REFRESH_TOKEN_REUSED');
      }
    });

    it('refresh answers revoked, reused and invalid tokens with 401 (never 400/403)', async () => {
      const address = email();
      await register(address);
      // Reused: a token rotated away, presented again (no logout).
      const reusedFam = await rotatedFamily(address);
      const reused = await refresh(reusedFam.tokens[0]!);
      expect([reused.status, reused.body.code]).toEqual([401, 'REFRESH_TOKEN_REUSED']);
      // Revoked: the current token after logout.
      const fam = await rotatedFamily(address);
      await logout(fam.access, fam.tokens[2]!).expect(204);
      const revoked = await refresh(fam.tokens[2]!);
      expect([revoked.status, revoked.body.code]).toEqual([401, 'REFRESH_TOKEN_REUSED']);
      // Invalid: unknown, and an empty-looking but well-formed string.
      for (const t of ['definitely-not-a-token', 'x'.repeat(43)]) {
        const invalid = await refresh(t);
        expect([invalid.status, invalid.body.code]).toEqual([401, 'REFRESH_TOKEN_INVALID']);
      }
    });

    it('logout with a pre-rotation token after the family is already revoked by reuse is still 204', async () => {
      const address = email();
      await register(address);
      const fam = await rotatedFamily(address);
      await refresh(fam.tokens[0]!).expect(401); // reuse detection revokes the family
      await logout(fam.access, fam.tokens[0]!).expect(204);
      await logout(fam.access, fam.tokens[1]!).expect(204);
      for (const t of fam.tokens) expect((await refresh(t)).status).toBe(401);
    });

    it("another user's token is not revoked (204, no change)", async () => {
      const a = (await register()).body;
      const b = (await register()).body;
      const bRot = (await refresh(b.refreshToken).expect(200)).body;
      await logout(a.accessToken, b.refreshToken).expect(204);
      await refresh(bRot.refreshToken).expect(200);
    });
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

  it('DELETE /me without a password is 400 with "password is required"', async () => {
    const me = (await register()).body;
    const res = await http().delete('/api/v1/me').set('Authorization', `Bearer ${me.accessToken}`).send({}).expect(400);
    expect(res.body.code).toBe('VALIDATION_FAILED');
    expect(res.body.details.fields.password).toBe('password is required');
    const empty = await http().delete('/api/v1/me').set('Authorization', `Bearer ${me.accessToken}`).send({ password: '' }).expect(400);
    expect(empty.body.details.fields.password).toBe('password is required');
  });

  it('rejects a deleted user’s still-unexpired access token with 401 UNAUTHENTICATED', async () => {
    const me = (await register()).body;
    await http().delete('/api/v1/me').set('Authorization', `Bearer ${me.accessToken}`).send({ password }).expect(204);
    const res = await http().post('/api/v1/auth/logout').set('Authorization', `Bearer ${me.accessToken}`).send({ refreshToken: me.refreshToken }).expect(401);
    expect(res.body.code).toBe('UNAUTHENTICATED');
    const again = await http().delete('/api/v1/me').set('Authorization', `Bearer ${me.accessToken}`).send({ password }).expect(401);
    expect(again.body.code).toBe('UNAUTHENTICATED');
  });

  it('revokes every refresh token explicitly on account deletion', async () => {
    const address = email();
    const me = (await register(address)).body;
    await http().post('/api/v1/auth/login').send({ email: address, password }).expect(200);
    const spy = jest.spyOn(app.get(TokenService), 'revokeAllForUser');
    try {
      await http().delete('/api/v1/me').set('Authorization', `Bearer ${me.accessToken}`).send({ password }).expect(204);
      expect(spy).toHaveBeenCalledWith(me.user.id, expect.anything());
    } finally {
      spy.mockRestore();
    }
    expect(await prisma.refreshToken.count({ where: { userId: me.user.id } })).toBe(0);
  });

  it('locks an account after repeated failed logins, even across IPs, and a success resets it', async () => {
    const limited = await createTestApp({ env: { LOGIN_MAX_FAILURES_PER_ACCOUNT: '2' } });
    try {
      const h = () => request(limited.getHttpServer());
      const address = email();
      await h().post('/api/v1/auth/register').send({ email: address, password }).expect(201);
      await h().post('/api/v1/auth/login').send({ email: address, password: 'wrong-wrong-1' }).expect(401);
      await h().post('/api/v1/auth/login').send({ email: address, password }).expect(200); // resets
      await h().post('/api/v1/auth/login').set('X-Forwarded-For', '203.0.113.1').send({ email: address, password: 'wrong-wrong-2' }).expect(401);
      await h().post('/api/v1/auth/login').set('X-Forwarded-For', '203.0.113.2').send({ email: address, password: 'wrong-wrong-3' }).expect(401);
      const locked = await h().post('/api/v1/auth/login').send({ email: address, password }).expect(429);
      expect(locked.body.code).toBe('RATE_LIMITED');
      expect(Number(locked.headers['retry-after'])).toBeGreaterThan(0);
      // Other accounts are unaffected.
      await h().post('/api/v1/auth/login').send({ email: email(), password }).expect(401);
    } finally {
      await limited.close();
    }
  });

  it('gives refresh its own, looser bucket', async () => {
    const limited = await createTestApp({ env: { AUTH_RATE_LIMIT_PER_MINUTE: '2', AUTH_REFRESH_RATE_LIMIT_PER_MINUTE: '4' } });
    try {
      const h = () => request(limited.getHttpServer());
      const statuses: number[] = [];
      for (let i = 0; i < 5; i += 1) statuses.push((await h().post('/api/v1/auth/refresh').send({ refreshToken: `nope-${i}` })).status);
      expect(statuses).toEqual([401, 401, 401, 401, 429]);
    } finally {
      await limited.close();
    }
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
