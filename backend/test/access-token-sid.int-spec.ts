/**
 * Access tokens carry `sid` = the refresh family (docs/specs/notification-sending.md, "Session binding").
 * Legacy tokens without `sid` still authenticate; `sid` is only ever taken from the signed token.
 */
import { randomUUID } from 'node:crypto';

import type { ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { JwtService } from '@nestjs/jwt';
import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { JwtAuthGuard, type AuthedRequest } from '../src/auth/jwt-auth.guard';
import { hashRefreshToken, JWT_AUDIENCE, JWT_ISSUER, TokenService } from '../src/auth/token.service';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp, testEnv } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const PASSWORD = 'correct horse battery';
const SECRET = testEnv().JWT_ACCESS_SECRET;

const payload = (jwt: string) => JSON.parse(Buffer.from(jwt.split('.')[1]!, 'base64url').toString('utf8')) as Record<string, unknown>;

describeDb('access token sid (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const jwt = new JwtService({});
  const http = () => request(app.getHttpServer());
  const familyOf = async (refreshToken: string) =>
    (await prisma.refreshToken.findUniqueOrThrow({ where: { tokenHash: hashRefreshToken(refreshToken) } })).familyId;
  const sign = (claims: Record<string, unknown>, secret = SECRET) =>
    jwt.signAsync(claims, { secret, algorithm: 'HS256', expiresIn: 900, issuer: JWT_ISSUER, audience: JWT_AUDIENCE, jwtid: randomUUID() });
  const me = (token: string) => http().get('/api/v1/me/profile').set('Authorization', `Bearer ${token}`);

  let user: { id: string; email: string; accessToken: string; refreshToken: string };
  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    const email = `sid-${randomUUID()}@example.in`;
    const body = (await http().post('/api/v1/auth/register').send({ email, password: PASSWORD }).expect(201)).body;
    user = { id: body.user.id, email, accessToken: body.accessToken, refreshToken: body.refreshToken };
  });
  afterAll(async () => {
    await app.close();
  });

  it('register, login and refresh tokens carry their refresh family as sid', async () => {
    const regFamily = await familyOf(user.refreshToken);
    expect(payload(user.accessToken).sid).toBe(regFamily);

    const login = (await http().post('/api/v1/auth/login').send({ email: user.email, password: PASSWORD }).expect(200)).body;
    const loginFamily = await familyOf(login.refreshToken);
    expect(loginFamily).not.toBe(regFamily);
    expect(payload(login.accessToken).sid).toBe(loginFamily);

    const rotated = (await http().post('/api/v1/auth/refresh').send({ refreshToken: login.refreshToken }).expect(200)).body;
    expect(await familyOf(rotated.refreshToken)).toBe(loginFamily);
    expect(payload(rotated.accessToken).sid).toBe(loginFamily);
    await me(rotated.accessToken).expect(200);

    expect(await app.get(TokenService).verifyAccess(rotated.accessToken)).toEqual({ userId: user.id, sid: loginFamily });
  });

  it('a legacy token without sid (signed the pre-sid way) still authenticates on /me routes', async () => {
    const legacy = await sign({ sub: user.id, typ: 'access' });
    expect(payload(legacy)).not.toHaveProperty('sid');
    await me(legacy).expect(200);
    await http().get('/api/v1/me/notification-preferences').set('Authorization', `Bearer ${legacy}`).expect(200);
    expect(await app.get(TokenService).verifyAccess(legacy)).toEqual({ userId: user.id });
  });

  it('rejects a sid not backed by a valid signature, or malformed', async () => {
    const forged = await sign({ sub: user.id, typ: 'access', sid: randomUUID() }, 'another-secret-0123456789abcdef-xyz');
    expect((await me(forged).expect(401)).body.code).toBe('UNAUTHENTICATED');

    // Swap the payload's sid but keep the original signature.
    const [h, , sig] = user.accessToken.split('.');
    const tampered = `${h}.${Buffer.from(JSON.stringify({ ...payload(user.accessToken), sid: randomUUID() })).toString('base64url')}.${sig}`;
    expect((await me(tampered).expect(401)).body.code).toBe('UNAUTHENTICATED');

    for (const sid of ['not-a-uuid', 42, null, '']) {
      const bad = await sign({ sub: user.id, typ: 'access', sid });
      expect({ sid, status: (await me(bad)).status }).toEqual({ sid, status: 401 });
    }
  });

  it('the request context gets sid only from the token, never from body, query, headers or a preset value', async () => {
    const guard = new JwtAuthGuard(app.get(Reflector), app.get(TokenService), prisma);
    const run = async (token: string) => {
      const req = {
        header: (n: string) => (n.toLowerCase() === 'authorization' ? `Bearer ${token}` : 'spoofed'),
        headers: { authorization: `Bearer ${token}`, 'x-session-id': randomUUID(), sid: randomUUID() },
        body: { sid: randomUUID(), sessionId: randomUUID() },
        query: { sid: randomUUID() },
        sessionId: randomUUID(), // e.g. set by something earlier in the chain
      } as unknown as AuthedRequest;
      const ctx = { getHandler: () => run, getClass: () => Object, switchToHttp: () => ({ getRequest: () => req }) } as unknown as ExecutionContext;
      await guard.canActivate(ctx);
      return req;
    };
    const current = await run(user.accessToken);
    expect(current.userId).toBe(user.id);
    expect(current.sessionId).toBe(await familyOf(user.refreshToken));

    const legacy = await run(await sign({ sub: user.id, typ: 'access' }));
    expect(legacy.userId).toBe(user.id);
    expect(legacy.sessionId).toBeUndefined();
  });
});
