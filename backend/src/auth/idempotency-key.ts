import { createParamDecorator, ExecutionContext, HttpStatus } from '@nestjs/common';
import type { Request } from 'express';

import { ApiError } from '../common/api-error';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** Optional `Idempotency-Key` header; must be a UUID when present. */
export const IdempotencyKeyHeader = createParamDecorator((_: unknown, ctx: ExecutionContext): string | undefined => {
  const value = ctx.switchToHttp().getRequest<Request>().header('idempotency-key');
  if (value === undefined || value === '') return undefined;
  if (!UUID.test(value)) {
    throw new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Idempotency-Key must be a UUID.', {
      fields: { 'Idempotency-Key': 'must be a UUID' },
    });
  }
  return value.toLowerCase();
});
