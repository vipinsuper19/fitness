/** Env windows for purge:expired: defaults, minimums, and garbage rejected (no DB). */
import { windowFromEnv } from '../src/maintenance/purge-expired';

describe('purge:expired windowFromEnv', () => {
  const read = (v?: string) => windowFromEnv('PURGE_NOTIFICATION_SENDS_DAYS', 90, 30, v === undefined ? {} : { PURGE_NOTIFICATION_SENDS_DAYS: v });
  it('defaults when unset or blank', () => {
    expect(read()).toBe(90);
    expect(read('  ')).toBe(90);
  });
  it('accepts values at or above the minimum', () => {
    expect(read('30')).toBe(30);
    expect(read('365')).toBe(365);
  });
  it('rejects values below the minimum instead of clamping', () => {
    expect(() => read('29')).toThrow(/at least 30/);
    expect(() => read('0')).toThrow(/at least 30/);
  });
  it('rejects non-integers', () => {
    for (const v of ['-5', '7.5', 'abc', '1e3']) expect(() => read(v)).toThrow(/whole number/);
  });
});
