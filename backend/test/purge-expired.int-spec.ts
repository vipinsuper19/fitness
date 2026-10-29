/**
 * purge:expired (src/maintenance/purge-expired.ts) against Postgres (DB_TESTS=1): dry-run deletes nothing and
 * counts right; execute deletes exactly the eligible rows; reuse detection still works after a purge.
 * Scoped to this file's users so other specs' rows never affect the counts.
 */
import { randomUUID } from 'node:crypto';

import type { NestExpressApplication } from '@nestjs/platform-express';
import request from 'supertest';

import { formatResult, purgeExpired } from '../src/maintenance/purge-expired';
import { hashRefreshToken } from '../src/auth/token.service';
import { PrismaService } from '../src/prisma/prisma.service';
import { createTestApp } from './helpers';

const run = process.env.DB_TESTS === '1' && !!process.env.DATABASE_URL;
const describeDb = run ? describe : describe.skip;
const PASSWORD = 'correct horse battery';
const DAY = 86_400_000;

describeDb('purge:expired', () => {
  let app: NestExpressApplication;
  let prisma: PrismaService;
  const http = () => request(app.getHttpServer());

  // notification_sends ships with push sending (not built yet). Until its migration exists, stand up a minimal
  // table with the spec's columns for this file only, and drop it afterwards (jest runs in band).
  let createdNs = false;
  beforeAll(async () => {
    app = await createTestApp();
    prisma = app.get(PrismaService);
    const exists = (await prisma.$queryRaw<Array<{ t: string | null }>>`SELECT to_regclass('notification_sends')::text AS t`)[0]!.t;
    if (exists === null) {
      await prisma.$executeRawUnsafe(`CREATE TABLE notification_sends (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        type text NOT NULL DEFAULT 'water_reminder', slot_key text NOT NULL, status text NOT NULL,
        due_at timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz)`);
      createdNs = true;
    }
  });
  afterAll(async () => {
    if (createdNs) await prisma.$executeRawUnsafe('DROP TABLE IF EXISTS notification_sends');
    await app.close();
  });

  const send = async (userId: string, status: string, createdAt: Date) =>
    (await prisma.$queryRaw<Array<{ id: string }>>`INSERT INTO notification_sends (user_id, slot_key, status, created_at)
      VALUES (${userId}::uuid, ${randomUUID()}, ${status}, ${createdAt}) RETURNING id::text AS id`)[0]!.id;
  const sendIds = async (ids: string[]) =>
    (await prisma.$queryRaw<Array<{ n: bigint }>>`SELECT count(*) AS n FROM notification_sends WHERE id = ANY(${ids}::uuid[])`)[0]!.n;

  const signUp = async () => {
    const b = (await http().post('/api/v1/auth/register').send({ email: `pg-${randomUUID()}@example.in`, password: PASSWORD }).expect(201)).body;
    return { id: b.user.id as string, refreshToken: b.refreshToken as string };
  };
  const ago = (days: number) => new Date(Date.now() - days * DAY);
  const token = (userId: string, familyId: string, o: { revokedDaysAgo?: number; expiresInDays: number }) =>
    prisma.refreshToken.create({
      data: {
        userId, familyId, tokenHash: hashRefreshToken(randomUUID()),
        expiresAt: new Date(Date.now() + o.expiresInDays * DAY),
        revokedAt: o.revokedDaysAgo === undefined ? null : ago(o.revokedDaysAgo),
      },
      select: { id: true },
    });
  // Keys are created 24 h before they expire (the TTL; the table checks expires_at > created_at).
  const key = (userId: string, expiresInHours: number) =>
    prisma.idempotencyKey.create({
      data: {
        createdAt: new Date(Date.now() + (expiresInHours - 24) * 3_600_000),
        scope: `user:${userId}`, key: randomUUID(), userId, method: 'POST', path: '/api/v1/goals', requestHash: '0'.repeat(64),
        expiresAt: new Date(Date.now() + expiresInHours * 3_600_000),
      },
      select: { id: true },
    });

  /** Builds a fixture; returns which row ids must go and which must stay. */
  async function fixture() {
    const u = await signUp(); // its sign-up family is live and must stay
    const doomed: string[] = [];
    const kept: string[] = [];
    // Dead family, revoked 10 and 30 days ago: both go.
    const dead = randomUUID();
    doomed.push((await token(u.id, dead, { revokedDaysAgo: 30, expiresInDays: 0 })).id);
    doomed.push((await token(u.id, dead, { revokedDaysAgo: 10, expiresInDays: 20 })).id);
    // Dead family, expired 9 days ago, never revoked: goes.
    doomed.push((await token(u.id, randomUUID(), { expiresInDays: -9 })).id);
    // Dead family but revoked only 2 days ago (inside the margin): stays.
    kept.push((await token(u.id, randomUUID(), { revokedDaysAgo: 2, expiresInDays: 28 })).id);
    // Family with a live token: an old revoked row stays (reuse detection), and so does the live one.
    const live = randomUUID();
    kept.push((await token(u.id, live, { revokedDaysAgo: 40, expiresInDays: -10 })).id);
    kept.push((await token(u.id, live, { expiresInDays: 20 })).id);
    // Idempotency keys: expired 1 h ago goes; still valid stays.
    const ikDoomed = [(await key(u.id, -1)).id, (await key(u.id, -48)).id];
    const ikKept = [(await key(u.id, 5)).id];
    return { u, doomed, kept, ikDoomed, ikKept };
  }

  it('dry-run (the default) deletes nothing and reports the eligible counts and cutoffs', async () => {
    const f = await fixture();
    const before = await prisma.refreshToken.count({ where: { userId: f.u.id } });
    const r = await purgeExpired(prisma, { onlyUserIds: [f.u.id] });
    expect(r.execute).toBe(false);
    expect(r.refreshTokens).toEqual({ eligible: 3, deleted: 0 });
    expect(r.idempotencyKeys).toEqual({ eligible: 2, deleted: 0 });
    expect(await prisma.refreshToken.count({ where: { userId: f.u.id } })).toBe(before);
    expect(await prisma.idempotencyKey.count({ where: { userId: f.u.id } })).toBe(3);
    const out = formatResult(r);
    expect(out).toMatch(/DRY RUN/);
    expect(out).toContain(r.cutoffs.refreshTokensDeadBefore);
    expect(out).not.toMatch(/[0-9a-f]{64}/); // no hashes
  });

  it('execute deletes exactly the eligible rows, in batches, and is idempotent', async () => {
    const f = await fixture();
    const r = await purgeExpired(prisma, { execute: true, batchSize: 1, onlyUserIds: [f.u.id] });
    expect(r.refreshTokens).toEqual({ eligible: 3, deleted: 3 });
    expect(r.idempotencyKeys).toEqual({ eligible: 2, deleted: 2 });
    expect(await prisma.refreshToken.count({ where: { id: { in: f.doomed } } })).toBe(0);
    expect(await prisma.refreshToken.count({ where: { id: { in: f.kept } } })).toBe(f.kept.length);
    expect(await prisma.idempotencyKey.count({ where: { id: { in: f.ikDoomed } } })).toBe(0);
    expect(await prisma.idempotencyKey.count({ where: { id: { in: f.ikKept } } })).toBe(1);
    // Sign-up token is live and still refreshes.
    await http().post('/api/v1/auth/refresh').send({ refreshToken: f.u.refreshToken }).expect(200);
    const again = await purgeExpired(prisma, { execute: true, onlyUserIds: [f.u.id] });
    expect(again.refreshTokens.deleted).toBe(0);
    expect(again.idempotencyKeys.deleted).toBe(0);
  });

  it('reuse detection still works after a purge: an old revoked token in a live family is kept and revokes it', async () => {
    const u = await signUp();
    const next = (await http().post('/api/v1/auth/refresh').send({ refreshToken: u.refreshToken }).expect(200)).body;
    // Make the rotated-away original look 30 days old.
    await prisma.refreshToken.update({ where: { tokenHash: hashRefreshToken(u.refreshToken) }, data: { revokedAt: ago(30) } });
    await purgeExpired(prisma, { execute: true, onlyUserIds: [u.id] });
    expect(await prisma.refreshToken.count({ where: { tokenHash: hashRefreshToken(u.refreshToken) } })).toBe(1);
    const reuse = await http().post('/api/v1/auth/refresh').send({ refreshToken: u.refreshToken }).expect(401);
    expect(reuse.body.code).toBe('REFRESH_TOKEN_REUSED');
    expect((await http().post('/api/v1/auth/refresh').send({ refreshToken: next.refreshToken }).expect(401)).body.code).toBe('REFRESH_TOKEN_REUSED');
    // Now the family is dead; once old enough a purge removes it all.
    const familyId = (await prisma.refreshToken.findUniqueOrThrow({ where: { tokenHash: hashRefreshToken(next.refreshToken) } })).familyId;
    await prisma.refreshToken.updateMany({ where: { familyId }, data: { revokedAt: ago(8) } });
    const r = await purgeExpired(prisma, { execute: true, onlyUserIds: [u.id] });
    expect(r.refreshTokens.deleted).toBe(2);
    expect(await prisma.refreshToken.count({ where: { familyId } })).toBe(0);
  });

  describe('notification_sends', () => {
    const NOW = new Date('2026-10-29T12:00:00.000Z');
    const daysBefore = (d: number, ms = 0) => new Date(NOW.getTime() - d * DAY + ms);
    async function nsFixture(days = 90) {
      const u = await signUp();
      const doomed = [
        await send(u.id, 'sent', daysBefore(days, -1)), // 1 ms past the cutoff
        await send(u.id, 'failed', daysBefore(days + 30)),
      ];
      const kept = [
        await send(u.id, 'sent', daysBefore(days)), // exactly at the cutoff: not older, stays
        await send(u.id, 'failed', daysBefore(days - 1)),
        // Non-final states stay at any age.
        await send(u.id, 'pending', daysBefore(400)),
        await send(u.id, 'sending', daysBefore(400)),
        await send(u.id, 'held', daysBefore(400)),
        // Other end states are out of scope for this purge.
        await send(u.id, 'expired', daysBefore(400)),
      ];
      return { u, doomed, kept };
    }

    it('dry run counts but deletes nothing', async () => {
      const f = await nsFixture();
      const r = await purgeExpired(prisma, { now: NOW, onlyUserIds: [f.u.id] });
      expect(r.notificationSends).toEqual({ present: true, eligible: 2, deleted: 0 });
      expect(Number(await sendIds([...f.doomed, ...f.kept]))).toBe(8);
      expect(formatResult(r)).toContain('notification_sends: 2 eligible, 0 deleted');
      expect(formatResult(r)).toContain(daysBefore(90).toISOString());
    });

    it('execute deletes only sent/failed rows older than the window, never pending/sending/held', async () => {
      const f = await nsFixture();
      const r = await purgeExpired(prisma, { now: NOW, execute: true, batchSize: 1, onlyUserIds: [f.u.id] });
      expect(r.notificationSends).toEqual({ present: true, eligible: 2, deleted: 2 });
      expect(Number(await sendIds(f.doomed))).toBe(0);
      expect(Number(await sendIds(f.kept))).toBe(f.kept.length);
      const again = await purgeExpired(prisma, { now: NOW, execute: true, onlyUserIds: [f.u.id] });
      expect(again.notificationSends.deleted).toBe(0);
    });

    it('honours a custom window at its boundary', async () => {
      const f = await nsFixture(45);
      const r = await purgeExpired(prisma, { now: NOW, execute: true, notificationSendsDays: 45, onlyUserIds: [f.u.id] });
      expect(r.notificationSends.deleted).toBe(2);
      expect(Number(await sendIds(f.kept))).toBe(f.kept.length);
    });

    it('reports "table not present" and deletes nothing when the table does not exist', async () => {
      if (!createdNs) return; // a real table exists; nothing to simulate
      await prisma.$executeRawUnsafe('ALTER TABLE notification_sends RENAME TO notification_sends_hidden');
      try {
        const r = await purgeExpired(prisma, { execute: true, onlyUserIds: [randomUUID()] });
        expect(r.notificationSends).toEqual({ present: false, eligible: 0, deleted: 0 });
        expect(formatResult(r)).toContain('notification_sends: table not present, skipped');
      } finally {
        await prisma.$executeRawUnsafe('ALTER TABLE notification_sends_hidden RENAME TO notification_sends');
      }
    });
  });

  it('idempotency keys: expired exactly now goes, 1 ms in the future stays', async () => {
    const u = await signUp();
    const now = new Date();
    const mk = (expiresAt: Date) => prisma.idempotencyKey.create({
      data: { createdAt: new Date(expiresAt.getTime() - DAY), scope: `user:${u.id}`, key: randomUUID(), userId: u.id,
        method: 'POST', path: '/api/v1/goals', requestHash: '0'.repeat(64), expiresAt }, select: { id: true } });
    const at = await mk(now);
    const after = await mk(new Date(now.getTime() + 1));
    const r = await purgeExpired(prisma, { now, execute: true, onlyUserIds: [u.id] });
    expect(r.idempotencyKeys.deleted).toBe(1);
    expect(await prisma.idempotencyKey.count({ where: { id: at.id } })).toBe(0);
    expect(await prisma.idempotencyKey.count({ where: { id: after.id } })).toBe(1);
  });

  it('rejects a bad batch size or window', async () => {
    await expect(purgeExpired(prisma, { batchSize: 0 })).rejects.toThrow(/batchSize/);
    await expect(purgeExpired(prisma, { marginDays: 0 })).rejects.toThrow(/marginDays/);
    await expect(purgeExpired(prisma, { notificationSendsDays: 29 })).rejects.toThrow(/notificationSendsDays/);
  });
});
