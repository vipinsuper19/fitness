import { roundHalfUp } from '../src/progress/progress.service';
import { planWeek } from '../src/workout-plans/plans.service';

describe('measurement rounding', () => {
  it('rounds half-up on the decimal value, despite binary floats', () => {
    expect(roundHalfUp(18.25, 1)).toBe(18.3);
    expect(roundHalfUp(30.05, 1)).toBe(30.1);
    expect(roundHalfUp(99.95, 1)).toBe(100);
    expect(roundHalfUp(84.24, 1)).toBe(84.2);
    expect(roundHalfUp(72.345, 2)).toBe(72.35);
    expect(roundHalfUp(72.5, 1)).toBe(72.5);
  });
});

describe('active plan week (local days)', () => {
  const tz = 'Asia/Kolkata';
  // Activated 23:30 IST on Mon 5 Oct (18:00 UTC).
  const at = new Date('2026-10-05T18:00:00Z');
  const now = (iso: string) => new Date(iso);

  it('counts local calendar days, not 24-hour blocks', () => {
    expect(planWeek(at, at, tz, 4)).toEqual({ currentWeek: 1, planEnded: false });
    // 00:30 IST on 12 Oct is 7 local days after the activation day: week 2 (only 25 hours after activation + 6 days).
    expect(planWeek(at, now('2026-10-11T19:00:00Z'), tz, 4)).toEqual({ currentWeek: 2, planEnded: false });
    expect(planWeek(at, now('2026-10-11T18:00:00Z'), tz, 4)).toEqual({ currentWeek: 1, planEnded: false });
  });

  it('caps at the plan length and flags the end', () => {
    expect(planWeek(at, now('2026-11-01T12:00:00Z'), tz, 4)).toEqual({ currentWeek: 4, planEnded: false }); // day 27
    expect(planWeek(at, now('2026-11-02T12:00:00Z'), tz, 4)).toEqual({ currentWeek: 4, planEnded: true }); // day 28
  });

  it('never goes below week 1 (clock skew)', () => {
    expect(planWeek(at, now('2026-10-01T00:00:00Z'), tz, 4)).toEqual({ currentWeek: 1, planEnded: false });
  });
});
