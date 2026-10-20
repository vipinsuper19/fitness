import { HttpStatus } from '@nestjs/common';

import { ApiError } from './api-error';

/** Opaque keyset cursors: base64url JSON. A tampered cursor is a 400, never a 500. */
export function encodeCursor(value: Record<string, string>): string {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Longest string value a cursor may carry (exercise names are VARCHAR(120)). */
export const CURSOR_VALUE_MAX = 120;

/**
 * Decodes and validates a cursor: every key must be a string of at most
 * CURSOR_VALUE_MAX chars, and keys listed in `uuidKeys` (default: `id`)
 * must be UUIDs, so a forged cursor can never reach the database as a
 * malformed uuid and turn into a 500.
 */
export function decodeCursor<K extends string>(
  cursor: string | undefined,
  keys: readonly K[],
  uuidKeys: readonly K[] = keys.filter((k) => k === 'id'),
): Record<K, string> | undefined {
  if (!cursor) return undefined;
  try {
    const parsed = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8')) as Record<string, unknown>;
    if (
      parsed &&
      typeof parsed === 'object' &&
      !Array.isArray(parsed) &&
      keys.every((k) => typeof parsed[k] === 'string' && (parsed[k] as string).length <= CURSOR_VALUE_MAX) &&
      uuidKeys.every((k) => UUID.test(parsed[k] as string))
    ) {
      return Object.fromEntries(keys.map((k) => [k, parsed[k] as string])) as Record<K, string>;
    }
  } catch {
    // fall through
  }
  throw new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', {
    fields: { cursor: 'cursor is not valid; start again without it' },
  });
}
