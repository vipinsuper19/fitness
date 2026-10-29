/**
 * Graceful shutdown (src/common/graceful-shutdown.ts, docs/DEPLOY.md). Real HTTP server on an ephemeral port and a
 * stub database whose query blocks until the test releases it, so a request is reliably in flight during shutdown.
 */
import { EventEmitter } from 'node:events';
import http from 'node:http';
import type { AddressInfo } from 'node:net';

import type { NestExpressApplication } from '@nestjs/platform-express';

import { gracefulShutdown, installShutdownSignals, shutdownState } from '../src/common/graceful-shutdown';
import { createTestApp } from './helpers';

type Events = string[];

function stubPrisma(events: Events) {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  const stub = {
    $connect: async () => {},
    $queryRaw: async () => {
      events.push('query:start');
      await gate;
      events.push('query:end');
      return [{ '?column?': 1 }];
    },
    $disconnect: async () => {
      events.push('prisma:disconnect');
    },
    async onModuleDestroy() {
      await stub.$disconnect();
    },
  };
  return { stub, release: () => release() };
}

function get(port: number, path: string): Promise<{ status: number; body: string; connection?: string }> {
  return new Promise((resolve, reject) => {
    const req = http.get({ host: '127.0.0.1', port, path, agent: false }, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve({ status: res.statusCode!, body, connection: res.headers.connection }));
    });
    req.on('error', reject);
  });
}

const tick = (ms = 50) => new Promise((r) => setTimeout(r, ms));

describe('graceful shutdown', () => {
  let app: NestExpressApplication;
  let port: number;
  let events: Events;
  let release: () => void;

  beforeEach(async () => {
    shutdownState.shuttingDown = false;
    events = [];
    const p = stubPrisma(events);
    release = p.release;
    app = await createTestApp({ prisma: p.stub });
    await app.listen(0, '127.0.0.1');
    port = (app.getHttpServer().address() as AddressInfo).port;
  });
  afterEach(async () => {
    release();
    shutdownState.shuttingDown = false;
    if (app.getHttpServer().listening) await app.close();
  });

  it('finishes the in-flight request, refuses new connections, then disconnects Prisma', async () => {
    const slow = get(port, '/health?db=true'); // blocks in $queryRaw
    await tick();
    expect(events).toEqual(['query:start']);

    const shutdown = gracefulShutdown(app, 5_000);
    await tick();
    await expect(get(port, '/health')).rejects.toMatchObject({ code: 'ECONNREFUSED' });
    expect(events).not.toContain('prisma:disconnect'); // not while a request still needs the database

    release();
    const res = await slow;
    expect(res.status).toBe(200);
    expect(JSON.parse(res.body)).toMatchObject({ status: 'ok', db: 'up' });
    expect(res.connection).toBe('close');
    await expect(shutdown).resolves.toBe('drained');
    expect(events).toEqual(['query:start', 'query:end', 'prisma:disconnect']);
  });

  it('is bounded: after the grace period the stuck socket is closed and Prisma still disconnects', async () => {
    const stuck = get(port, '/health?db=true').then(
      () => 'answered',
      (e: NodeJS.ErrnoException) => e.code,
    );
    await tick();
    const started = Date.now();
    await expect(gracefulShutdown(app, 300)).resolves.toBe('timeout');
    expect(Date.now() - started).toBeLessThan(2_000);
    expect(await stuck).toBe('ECONNRESET');
    expect(events).toContain('prisma:disconnect');
  });

  it('SIGTERM handler drains and exits 0 (once, even if signalled twice)', async () => {
    const proc = new EventEmitter();
    const exit = jest.fn();
    installShutdownSignals(app, 5_000, proc as unknown as NodeJS.Process, exit);
    const slow = get(port, '/health?db=true');
    await tick();
    proc.emit('SIGTERM', 'SIGTERM');
    proc.emit('SIGINT', 'SIGINT');
    await tick();
    expect(exit).not.toHaveBeenCalled();
    release();
    expect((await slow).status).toBe(200);
    for (let i = 0; i < 40 && exit.mock.calls.length === 0; i += 1) await tick();
    expect(exit.mock.calls).toEqual([[0]]);
    expect(events).toEqual(['query:start', 'query:end', 'prisma:disconnect']);
  });
});
