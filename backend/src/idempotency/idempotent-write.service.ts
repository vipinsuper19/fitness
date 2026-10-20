import { Injectable } from '@nestjs/common';
import type { Request, Response } from 'express';

import { IdempotencyService, requestHash } from './idempotency.service';

export interface IdempotentWrite<T> {
  userId: string;
  /** Validated `Idempotency-Key` header, if the client sent one. */
  key: string | undefined;
  req: Request;
  res: Response;
  /** Success status of the handler (200, 201 or 204). */
  status: number;
  handler: () => Promise<T>;
}

/** Stored in place of a body for 204 responses, so the row still replays. */
const NO_CONTENT = { noContent: true } as const;

/**
 * Wraps a signed-in write in the insert-first idempotency protocol:
 * - no key: just run;
 * - first request: run, then store the status and body for 24 h;
 * - replay (same key, method, path and body): send the stored response with
 *   `Idempotent-Replayed: true`, without running the handler again;
 * - a failure frees the key so the client can retry.
 * The hash covers the raw JSON body, so a different body is
 * `409 IDEMPOTENCY_KEY_REUSED`.
 */
@Injectable()
export class IdempotentWriteService {
  constructor(private readonly idempotency: IdempotencyService) {}

  async run<T>(w: IdempotentWrite<T>): Promise<T | undefined> {
    w.res.status(w.status);
    if (!w.key) return w.handler();

    const start = await this.idempotency.begin({
      scope: `user:${w.userId}`,
      userId: w.userId,
      key: w.key,
      method: w.req.method,
      path: w.req.originalUrl.split('?')[0]!,
      requestHash: requestHash(w.req.body ?? null),
    });

    if (start.kind === 'replay') {
      w.res.status(start.status);
      w.res.setHeader('Idempotent-Replayed', 'true');
      return start.status === 204 ? undefined : (start.body as T);
    }
    if (start.kind === 'rerun') return w.handler();

    try {
      const body = await w.handler();
      await this.idempotency.complete(start.recordId, { status: w.status, body: w.status === 204 ? NO_CONTENT : body });
      return body;
    } catch (error) {
      await this.idempotency.abandon(start.recordId).catch(() => undefined);
      throw error;
    }
  }
}
