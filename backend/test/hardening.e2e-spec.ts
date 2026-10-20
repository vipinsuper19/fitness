import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { createTestApp } from './helpers';

/** HTTP hardening without a database: helmet, body limit, validation, error shape, auth guard. */
describe('app hardening', () => {
  let app: NestExpressApplication;
  const prisma = {
    $queryRaw: async () => [{ ok: 1 }],
    $disconnect: async () => undefined,
  };

  beforeAll(async () => {
    app = await createTestApp({ prisma });
  });
  afterAll(async () => {
    await app.close();
  });

  it('sets helmet security headers and echoes a request id', async () => {
    const res = await request(app.getHttpServer()).get('/health').set('X-Request-Id', 'client-req-12345').expect(200);
    expect(res.headers['x-content-type-options']).toBe('nosniff');
    expect(res.headers['x-frame-options']).toBeDefined();
    expect(res.headers['x-powered-by']).toBeUndefined();
    expect(res.headers['x-request-id']).toBe('client-req-12345');
  });

  it('rejects bodies over 100kb with 413 PAYLOAD_TOO_LARGE in the error shape', async () => {
    const res = await request(app.getHttpServer())
      .post('/api/v1/auth/login')
      .set('Content-Type', 'application/json')
      .send(JSON.stringify({ email: 'a@b.in', password: 'x'.repeat(110_000) }))
      .expect(413);
    expect(res.body).toMatchObject({ code: 'PAYLOAD_TOO_LARGE' });
    expect(typeof res.body.requestId).toBe('string');
  });

  it('returns 400 for malformed JSON', async () => {
    const res = await request(app.getHttpServer())
      .post('/api/v1/auth/login')
      .set('Content-Type', 'application/json')
      .send('{"email":')
      .expect(400);
    expect(res.body.code).toBe('VALIDATION_FAILED');
  });

  it('validates DTOs globally: bad fields and unknown fields are VALIDATION_FAILED with details', async () => {
    const res = await request(app.getHttpServer())
      .post('/api/v1/auth/register')
      .send({ email: 'not-an-email', password: 'short', isAdmin: true })
      .expect(400);
    expect(res.body.code).toBe('VALIDATION_FAILED');
    expect(Object.keys(res.body.details.fields)).toEqual(expect.arrayContaining(['email', 'password', 'isAdmin']));
    expect(res.body.requestId).toBe(res.headers['x-request-id']);
  });

  it('rejects a malformed Idempotency-Key', async () => {
    const res = await request(app.getHttpServer())
      .post('/api/v1/auth/register')
      .set('Idempotency-Key', 'not-a-uuid')
      .send({ email: 'a@b.in', password: 'long-enough-password' })
      .expect(400);
    expect(res.body.details.fields['Idempotency-Key']).toBeDefined();
  });

  it('requires a bearer access token on protected routes', async () => {
    const missing = await request(app.getHttpServer()).post('/api/v1/auth/logout').send({ refreshToken: 'x' }).expect(401);
    expect(missing.body.code).toBe('UNAUTHENTICATED');
    const forged = await request(app.getHttpServer())
      .delete('/api/v1/me')
      .set('Authorization', 'Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.sig')
      .send({ password: 'x' })
      .expect(401);
    expect(forged.body.code).toBe('UNAUTHENTICATED');
  });

  it('returns NOT_FOUND in the error shape for unknown routes', async () => {
    const res = await request(app.getHttpServer()).get('/api/v1/nope').expect(404);
    expect(res.body).toMatchObject({ code: 'NOT_FOUND' });
    expect(typeof res.body.requestId).toBe('string');
  });
});
