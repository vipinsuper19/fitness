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

/** Local calendar date (YYYY-MM-DD) of an instant in `tz`. */
export function localDate(t: Date, tz: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(t);
}

const DAY_MS = 86_400_000;

/** Inclusive day count from `from` to `to` (0 or less when from > to), computed arithmetically. */
export function daySpan(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY_MS) + 1;
}

/**
 * Inclusive list of calendar dates from `from` to `to`. Compares epoch milliseconds (not strings, which
 * mis-sort past year 9999) and refuses to build more than `maxDays` entries.
 */
export function dateRange(from: string, to: string, maxDays = 400): string[] {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (!Number.isFinite(start) || !Number.isFinite(end)) throw new RangeError('dateRange: invalid date');
  if ((end - start) / DAY_MS + 1 > maxDays) throw new RangeError(`dateRange: more than ${maxDays} days`);
  const out: string[] = [];
  for (let t = start; t <= end && out.length < maxDays; t += DAY_MS) out.push(new Date(t).toISOString().slice(0, 10));
  return out;
}

/** Previous calendar date (YYYY-MM-DD). */
export function prevDate(date: string): string {
  const d = new Date(`${date}T00:00:00.000Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}
