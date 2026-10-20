import { HttpStatus } from '@nestjs/common';

import { ApiError } from './api-error';

/** Opaque keyset cursors: base64url JSON. A tampered cursor is a 400, never a 500. */
export function encodeCursor(value: Record<string, string>): string {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

export function decodeCursor<K extends string>(cursor: string | undefined, keys: readonly K[]): Record<K, string> | undefined {
  if (!cursor) return undefined;
  try {
    const parsed = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8')) as Record<string, unknown>;
    if (parsed && typeof parsed === 'object' && keys.every((k) => typeof parsed[k] === 'string')) {
      return parsed as Record<K, string>;
    }
  } catch {
    // fall through
  }
  throw new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', {
    fields: { cursor: 'cursor is not valid; start again without it' },
  });
}
