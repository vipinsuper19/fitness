import type { Server } from 'node:http';

import { Logger } from '@nestjs/common';
import type { INestApplication } from '@nestjs/common';
import type { NextFunction, Request, Response } from 'express';

/**
 * Graceful shutdown (docs/DEPLOY.md "Graceful shutdown"). On SIGTERM/SIGINT:
 *  1. stop accepting new connections (`server.close()`), close idle keep-alive sockets, and answer anything still
 *     arriving on a busy keep-alive socket with `Connection: close`;
 *  2. let in-flight requests finish, up to `graceMs` (then the remaining sockets are destroyed);
 *  3. `app.close()`: Nest's destroy/shutdown hooks run only now, so PrismaService disconnects after the last response;
 *  4. exit 0.
 * Nest's own `enableShutdownHooks()` is NOT used: it runs onModuleDestroy (Prisma $disconnect) while the HTTP server is
 * still open and in-flight requests still need the database, then re-raises the signal (exit 143, not 0).
 */
export const shutdownState = { shuttingDown: false };

/** While draining, tell keep-alive clients to reconnect elsewhere instead of reusing this socket. */
export function connectionCloseWhileDraining(_req: Request, res: Response, next: NextFunction): void {
  if (shutdownState.shuttingDown) res.setHeader('Connection', 'close');
  next();
}

export type ShutdownResult = 'drained' | 'timeout';

export async function gracefulShutdown(app: INestApplication, graceMs: number, logger = new Logger('Shutdown')): Promise<ShutdownResult> {
  shutdownState.shuttingDown = true;
  const server = app.getHttpServer() as Server;
  const closed = new Promise<void>((resolve) => server.close(() => resolve()));
  server.closeIdleConnections();
  let timer: NodeJS.Timeout | undefined;
  const timedOut = new Promise<'timeout'>((resolve) => {
    timer = setTimeout(() => resolve('timeout'), graceMs);
  });
  const result: ShutdownResult = await Promise.race([closed.then(() => 'drained' as const), timedOut]);
  clearTimeout(timer);
  if (result === 'timeout') {
    logger.warn(`in-flight requests still open after ${graceMs} ms; closing their sockets`);
    server.closeAllConnections();
  }
  await app.close(); // module destroy hooks: Prisma $disconnect, timers
  logger.log(`shutdown complete (${result})`);
  return result;
}

type SignalSource = Pick<NodeJS.Process, 'once'>;

/** Installs the SIGTERM/SIGINT handler (main.ts). `proc`/`exit` are injectable for tests. */
export function installShutdownSignals(
  app: INestApplication,
  graceMs: number,
  proc: SignalSource = process,
  exit: (code: number) => void = (code) => process.exit(code),
): void {
  let started = false;
  const onSignal = (signal: string) => {
    if (started) return;
    started = true;
    new Logger('Shutdown').log(`${signal} received; draining (grace ${graceMs} ms)`);
    gracefulShutdown(app, graceMs).then(
      () => exit(0),
      () => exit(1),
    );
  };
  proc.once('SIGTERM', onSignal);
  proc.once('SIGINT', onSignal);
}
