/**
 * GET /xp/ledger keyset paging (row comparison `(created_at, id) < (t, i)`, docs/specs/index-audit.md)
 * against Postgres (DB_TESTS=1). The clock is frozen (Date only), so most ledger rows share one
 * created_at and only the id tie-break separates them. Paging at several page sizes must return
 * exactly the full list from the previous query (Prisma, ORDER BY created_at DESC, id DESC):
 * no duplicates, no gaps, same order.
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const NOW = new Date('2026-10-29T06:30:00Z');
const DATE_ONLY: Parameters<typeof jest.useFakeTimers>[0] = {
  now: NOW,
  doNotFake: ['nextTick', 'setImmediate', 'clearImmediate', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'queueMicrotask', 'hrtime', 'performance'],
};

describeDb('XP ledger keyset paging (HTTP + Postgres)', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());
  const signUp = async () => {
    const b = (await http().post('/api/v1/auth/register').send({ email: `xpl-${randomUUID()}@example.in`, password: 'correct horse battery' }).expect(201)).body;
    return { token: b.accessToken as string, id: b.user.id as string };
  };
  /** Ledger rows written with the frozen clock: `n` rows at `new Date()` (+ offset ms). */
  const write = (userId: string, n: number, offsetMs = 0) =>
    prisma.xPTransaction.createMany({
      data: Array.from({ length: n }, () => ({
        userId, sourceType: 'adjustment' as const, sourceId: randomUUID(), amount: 5, reason: 'paging test',
        createdAt: new Date(Date.now() + offsetMs),
      })),
    });
  type Entry = { id: string; createdAt: string };
  const pageAll = async (token: string, limit: number) => {
    const all: Entry[] = [];
    let cursor: string | null = null;
    let pages = 0;
    do {
      const q: string = `limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
      const body = (await http().get(`/api/v1/xp/ledger?${q}`).set('Authorization', `Bearer ${token}`).expect(200)).body as { items: Entry[]; nextCursor: string | null };
      expect(body.items.length).toBeLessThanOrEqual(limit);
      if (body.nextCursor) expect(body.items).toHaveLength(limit);
      all.push(...body.items);
      cursor = body.nextCursor;
      pages += 1;
      expect(pages).toBeLessThan(1000); // never loops
    } while (cursor);
    return { all, pages };
  };

  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
  });
  beforeEach(() => jest.useFakeTimers(DATE_ONLY));
  afterEach(() => jest.useRealTimers());
  afterAll(() => app.close());

  it('pages through ties at one created_at with no dupes, no gaps and the old order, at several page sizes', async () => {
    const u = await signUp();
    const other = await signUp();
    await write(u.id, 90); // 90 rows at exactly NOW: only the id orders them
    await write(u.id, 7, 1); // 1 ms newer: the cursor must keep millisecond precision
    await write(u.id, 12, -60_000); // a minute older
    await write(u.id, 1, -120_000);
    await write(other.id, 25); // another user's rows at the same instant never leak in

    // The full list from the previous query (Prisma's OR-form paging ordered the same way).
    const expected = (
      await prisma.xPTransaction.findMany({ where: { userId: u.id }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }] })
    ).map((r) => ({ id: r.id, createdAt: r.createdAt.toISOString() }));
    expect(expected).toHaveLength(110);
    expect(new Set(expected.filter((e) => e.createdAt === NOW.toISOString()).map((e) => e.id)).size).toBe(90);

    for (const limit of [1, 3, 7, 20, 89, 90, 100]) {
      const { all, pages } = await pageAll(u.token, limit);
      expect({ limit, ids: all.map((e) => e.id) }).toEqual({ limit, ids: expected.map((e) => e.id) });
      expect(all.map((e) => e.createdAt)).toEqual(expected.map((e) => e.createdAt));
      expect(new Set(all.map((e) => e.id)).size).toBe(all.length);
      expect(pages).toBe(Math.ceil(110 / limit));
    }
  });

  it('keeps the response shape and cursor format: items in the contract shape, an opaque cursor that round-trips', async () => {
    const u = await signUp();
    await write(u.id, 3);
    const first = (await http().get('/api/v1/xp/ledger?limit=2').set('Authorization', `Bearer ${u.token}`).expect(200)).body;
    expect(Object.keys(first).sort()).toEqual(['items', 'nextCursor']);
    expect(Object.keys(first.items[0]).sort()).toEqual(['amount', 'createdAt', 'id', 'reason', 'sourceId', 'sourceType']);
    expect(first.items[0]).toMatchObject({ sourceType: 'adjustment', amount: 5, reason: 'paging test', createdAt: NOW.toISOString() });
    // Same cursor format as before: base64url JSON { t, id } of the last item.
    const decoded = JSON.parse(Buffer.from(first.nextCursor, 'base64url').toString('utf8'));
    expect(decoded).toEqual({ t: first.items[1].createdAt, id: first.items[1].id });
    const second = (await http().get(`/api/v1/xp/ledger?limit=2&cursor=${first.nextCursor}`).set('Authorization', `Bearer ${u.token}`).expect(200)).body;
    expect(second.items).toHaveLength(1);
    expect(second.nextCursor).toBeNull();
  });
});
