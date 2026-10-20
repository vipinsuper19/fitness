import { dateRange, daySpan } from '../src/common/time';

describe('dateRange / daySpan', () => {
  it('lists inclusive days and counts spans arithmetically', () => {
    expect(dateRange('2026-02-27', '2026-03-01')).toEqual(['2026-02-27', '2026-02-28', '2026-03-01']);
    expect(dateRange('2026-03-02', '2026-03-01')).toEqual([]);
    expect(daySpan('2026-01-01', '2026-12-31')).toBe(365);
    expect(daySpan('2026-01-01', '9999-12-31')).toBeGreaterThan(366);
  });

  it('never loops past the cap, even across year 9999', () => {
    expect(() => dateRange('2026-01-01', '9999-12-31')).toThrow(RangeError);
    expect(dateRange('9999-12-30', '9999-12-31')).toEqual(['9999-12-30', '9999-12-31']);
    expect(() => dateRange('x', '2026-01-01')).toThrow(RangeError);
  });
});
