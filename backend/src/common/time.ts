import { HttpStatus } from '@nestjs/common';

import { ApiError } from './api-error';

/** Offset of `tz` from UTC at instant `t`, in ms (local - UTC). */
function offsetMs(t: number, tz: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).formatToParts(new Date(t));
  const get = (type: string) => Number(parts.find((p) => p.type === type)!.value);
  const local = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second'));
  return local - Math.floor(t / 1000) * 1000;
}

/** The UTC instant at which local calendar date `date` (YYYY-MM-DD) starts in `tz`. */
export function startOfLocalDay(date: string, tz: string): Date {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  const guess = Date.UTC(y, m - 1, d);
  let t = guess - offsetMs(guess, tz);
  t = guess - offsetMs(t, tz); // second pass settles DST edges
  return new Date(t);
}

/** Next local calendar date (YYYY-MM-DD). */
export function nextDate(date: string): string {
  const d = new Date(`${date}T00:00:00.000Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/** Client clocks may run a little fast; anything further ahead is rejected. */
export const CLOCK_SKEW_MS = 5 * 60_000;

export function clientTime(value: string | undefined, field: string, now: Date): Date | undefined {
  if (value === undefined) return undefined;
  const t = new Date(value);
  if (t.getTime() > now.getTime() + CLOCK_SKEW_MS) {
    throw new ApiError(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', 'Some fields are invalid.', {
      fields: { [field]: `${field} cannot be in the future` },
    });
  }
  return t;
}
