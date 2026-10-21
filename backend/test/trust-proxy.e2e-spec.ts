import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { createTestApp } from './helpers';

/**
 * Regression for the live Render chain: client -> Cloudflare edge -> Render 10.x -> 127.0.0.1 socket.
 * With TRUST_PROXY_HOPS=3, req.ip is the client and the per-IP auth throttle holds.
 */
describe('trust proxy with 3 hops (Cloudflare + Render)', () => {
  let app: NestExpressApplication;
  const prisma = {
    $queryRaw: async () => [{ ok: 1 }],
    $disconnect: async () => undefined,
  };
  const C = '203.0.113.7';

  beforeEach(async () => {
    app = await createTestApp({
      prisma,
      env: { TRUST_PROXY_HOPS: '3', AUTH_RATE_LIMIT_PER_MINUTE: '10' },
    });
  });
  afterEach(async () => {
    await app.close();
  });

  // Invalid body: the throttler guard runs before validation, so each call is counted without a DB.
  const login = (xff: string) =>
    request(app.getHttpServer())
      .post('/api/v1/auth/login')
      .set('X-Forwarded-For', xff)
      .send({ email: 'bad', password: 'x' });
  const remaining = (res: request.Response) =>
    Number(res.headers['x-ratelimit-remaining-auth']);

  it('resolves req.ip to the client', () => {
    const express = app.getHttpAdapter().getInstance();
    expect(express.get('trust proxy')).toBe(3);
    // Same walk Express (proxy-addr) does for req.ip: socket first, then XFF right to left, while trusted.
    const trust = express.get('trust proxy fn') as (addr: string, i: number) => boolean;
    const resolve = (socket: string, xff: string) => {
      const addrs = [socket, ...xff.split(',').map((s) => s.trim()).reverse()];
      let i = 0;
      while (i < addrs.length - 1 && trust(addrs[i] as string, i)) i++;
      return addrs[i];
    };
    expect(resolve('127.0.0.1', `${C}, 172.70.1.1, 10.1.2.3`)).toBe(C);
    expect(resolve('127.0.0.1', `1.2.3.4, ${C}, 172.70.1.1, 10.1.2.3`)).toBe(C);
  });

  it('counts down one bucket for a fixed client while the edge and Render IPs rotate', async () => {
    const counts: number[] = [];
    for (const [edge, internal] of [
      ['172.70.1.1', '10.1.2.3'],
      ['172.70.9.9', '10.4.5.6'],
      ['162.158.3.3', '10.7.8.9'],
    ]) {
      counts.push(remaining(await login(`${C}, ${edge}, ${internal}`)));
    }
    expect(counts).toEqual([9, 8, 7]);
  });

  it('keeps the same bucket when a spoofed entry is prepended, and blocks at the limit', async () => {
    expect(remaining(await login(`${C}, 172.70.1.1, 10.1.2.3`))).toBe(9);
    expect(remaining(await login(`1.2.3.4, ${C}, 172.70.2.2, 10.1.2.4`))).toBe(
      8,
    );
    expect(
      remaining(await login(`7.7.7.7, 1.2.3.4, ${C}, 172.70.3.3, 10.1.2.5`)),
    ).toBe(7);
    for (let i = 0; i < 7; i++) await login(`${C}, 172.70.1.1, 10.1.2.3`);
    expect((await login(`9.9.9.9, ${C}, 172.70.4.4, 10.1.2.6`)).status).toBe(
      429,
    );
    // A different real client still has its own budget.
    expect(remaining(await login('198.51.100.9, 172.70.1.1, 10.1.2.3'))).toBe(
      9,
    );
  });
});
