import { createHash } from 'node:crypto';

import { ConflictException, Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';

import { Prisma } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';

export const IDEMPOTENCY_TTL_MS = 24 * 60 * 60 * 1000;
const CLEANUP_INTERVAL_MS = 60 * 60 * 1000;

/** Responses carrying tokens are never stored or replayed. */
const TOKEN_BEARING_PATH = /\/auth\/(?:register|login|refresh)\/?$/;

export function isTokenBearingPath(path: string): boolean {
  return TOKEN_BEARING_PATH.test(path);
}

/** SHA-256 of a canonical JSON body (keys sorted), so key order does not matter. */
export function requestHash(body: unknown): string {
  return createHash('sha256').update(canonicalJson(body ?? null)).digest('hex');
}

function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonicalJson(v)}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

export interface IdempotencyRequest {
  /** `user:<id>` when signed in, `anon:register` for sign-up. */
  scope: string;
  userId?: string | null;
  key: string;
  method: string;
  path: string;
  requestHash: string;
}

export type IdempotencyStart =
  /** Run the handler, then call complete() or abandon() with recordId. */
  | { kind: 'proceed'; recordId: string }
  /** Same request already finished: send this stored response. */
  | { kind: 'replay'; status: number; body: unknown }
  /**
   * Same request finished but its response was not storable (sign-up):
   * run the handler again. For register that yields 409 EMAIL_TAKEN or
   * fresh tokens. There is nothing to complete afterwards.
   */
  | { kind: 'rerun' };

export class IdempotencyConflictException extends ConflictException {
  constructor(public readonly code: 'IDEMPOTENCY_IN_PROGRESS' | 'IDEMPOTENCY_KEY_REUSED') {
    super({
      code,
      message:
        code === 'IDEMPOTENCY_IN_PROGRESS'
          ? 'A request with this Idempotency-Key is still running. Retry shortly.'
          : 'This Idempotency-Key was used for a different request.',
    });
  }
}

function isUniqueViolation(error: unknown): boolean {
  return error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002';
}

/**
 * Insert-first idempotency. The `in_progress` row is inserted before the
 * handler runs; the (scope, key) unique index makes concurrent retries
 * collide (P2002) instead of both running.
 */
@Injectable()
export class IdempotencyService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(IdempotencyService.name);
  private timer: NodeJS.Timeout | undefined;

  constructor(private readonly prisma: PrismaService) {}

  async begin(req: IdempotencyRequest, now = new Date()): Promise<IdempotencyStart> {
    const method = req.method.toUpperCase();
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const row = await this.prisma.idempotencyKey.create({
          data: {
            scope: req.scope,
            key: req.key,
            userId: req.userId ?? null,
            method,
            path: req.path,
            requestHash: req.requestHash,
            expiresAt: new Date(now.getTime() + IDEMPOTENCY_TTL_MS),
            createdAt: now,
          },
          select: { id: true },
        });
        return { kind: 'proceed', recordId: row.id };
      } catch (error) {
        if (!isUniqueViolation(error)) throw error;
      }

      const existing = await this.prisma.idempotencyKey.findUnique({
        where: { scope_key: { scope: req.scope, key: req.key } },
      });
      if (!existing) continue; // removed between insert and read (abandoned); try again
      if (existing.expiresAt <= now) {
        // An expired key is free again; the guarded delete keeps a racing fresh row.
        await this.prisma.idempotencyKey.deleteMany({ where: { id: existing.id, expiresAt: { lte: now } } });
        continue;
      }
      if (existing.method !== method || existing.path !== req.path || existing.requestHash !== req.requestHash) {
        throw new IdempotencyConflictException('IDEMPOTENCY_KEY_REUSED');
      }
      if (existing.status === 'in_progress') {
        throw new IdempotencyConflictException('IDEMPOTENCY_IN_PROGRESS');
      }
      if (existing.responseStatus != null && existing.responseBody != null) {
        return { kind: 'replay', status: existing.responseStatus, body: existing.responseBody };
      }
      return { kind: 'rerun' };
    }
    throw new IdempotencyConflictException('IDEMPOTENCY_IN_PROGRESS');
  }

  /**
   * Marks the request done. Token-bearing paths never store the body.
   * Pass `userId` for sign-up so the row is erased with the account.
   */
  async complete(
    recordId: string,
    result: { status: number; body: unknown; userId?: string },
  ): Promise<void> {
    const row = await this.prisma.idempotencyKey.findUnique({ where: { id: recordId }, select: { path: true, scope: true } });
    if (!row) return;
    const storable = !isTokenBearingPath(row.path) && !row.scope.startsWith('anon:');
    await this.prisma.idempotencyKey.update({
      where: { id: recordId },
      data: {
        status: 'completed',
        responseStatus: result.status,
        responseBody: storable ? (result.body as Prisma.InputJsonValue) : Prisma.DbNull,
        ...(result.userId ? { userId: result.userId } : {}),
      },
    });
  }

  /** The handler failed: free the key so the client can retry. */
  async abandon(recordId: string): Promise<void> {
    await this.prisma.idempotencyKey.deleteMany({ where: { id: recordId, status: 'in_progress' } });
  }

  /** Deletes expired rows (including orphaned anonymous sign-up rows). */
  async purgeExpired(now = new Date()): Promise<number> {
    const { count } = await this.prisma.idempotencyKey.deleteMany({ where: { expiresAt: { lte: now } } });
    return count;
  }

  onModuleInit(): void {
    if (process.env.NODE_ENV === 'test') return;
    this.timer = setInterval(() => {
      this.purgeExpired().catch((error: unknown) =>
        this.logger.warn(`idempotency cleanup failed: ${error instanceof Error ? error.name : 'Error'}`),
      );
    }, CLEANUP_INTERVAL_MS);
    this.timer.unref();
  }

  onModuleDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }
}
