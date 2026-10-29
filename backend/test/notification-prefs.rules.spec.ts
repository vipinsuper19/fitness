import {
  DEFAULT_TYPES, DEFAULTS, effectiveTypes, isQuietAt, merge, NOTIFICATION_TYPES, parsePatch, samePrefs, storedTypes,
} from '../src/notification-prefs/notification-prefs.rules';

const q = (start: string, end: string, enabled = true) => ({ enabled, start, end });
const ist = (hhmm: string) => new Date(`2026-10-29T${hhmm}:00+05:30`);

describe('notification preference rules', () => {
  it('defaults: on for workout, streak, challenge, badge, progression; off for water and food; quiet 22:00-07:00', () => {
    expect(DEFAULTS).toEqual({
      enabled: true,
      types: { workout_reminder: true, streak_reminder: true, water_reminder: false, food_log_reminder: false, challenge_updates: true, badge_earned: true, progression_suggestion: true },
      quietHours: { enabled: true, start: '22:00', end: '07:00' },
    });
    expect(Object.keys(DEFAULT_TYPES)).toEqual([...NOTIFICATION_TYPES]);
  });

  it('stored flags merge over defaults; removed or malformed flags are ignored and dropped', () => {
    expect(effectiveTypes({ water_reminder: true, sms_digest: true, badge_earned: 'no' })).toEqual({ ...DEFAULT_TYPES, water_reminder: true });
    expect(storedTypes({ water_reminder: true, sms_digest: true })).toEqual({ water_reminder: true });
    // A type the user never set reads its (current) default.
    expect(effectiveTypes({})).toEqual(DEFAULT_TYPES);
    expect(effectiveTypes(null)).toEqual(DEFAULT_TYPES);
  });

  it('parses a valid partial patch; {} is a no-op', () => {
    expect(parsePatch({})).toEqual({ patch: {} });
    expect(parsePatch({ enabled: false, types: { water_reminder: true }, quietHours: { start: '23:30' } })).toEqual({
      patch: { enabled: false, types: { water_reminder: true }, quietHours: { start: '23:30' } },
    });
  });

  it.each([
    [{ types: { foo: true } }, 'types.foo', 'unknown notification type'],
    [{ types: { sms_digest: false } }, 'types.sms_digest', 'unknown notification type'], // a removed type is unknown
    [{ enabled: 'yes' }, 'enabled', 'boolean'],
    [{ types: { badge_earned: 1 } }, 'types.badge_earned', 'boolean'],
    [{ quietHours: { enabled: null } }, 'quietHours.enabled', 'null'],
    [{ enabled: null }, 'enabled', 'null'],
    [{ types: null }, 'types', 'null'],
    [{ quietHours: null }, 'quietHours', 'null'],
    [{ quietHours: { start: '24:00' } }, 'quietHours.start', 'HH:MM'],
    [{ quietHours: { start: '7:00' } }, 'quietHours.start', 'HH:MM'],
    [{ quietHours: { end: '07:60' } }, 'quietHours.end', 'HH:MM'],
    [{ quietHours: { end: null } }, 'quietHours.end', 'null'],
    [{ quietHours: { tz: 'UTC' } }, 'quietHours.tz', 'should not exist'],
    [{ timezone: 'UTC' }, 'timezone', 'should not exist'],
    [{ updatedAt: null }, 'updatedAt', 'should not exist'],
    [{ extra: 1 }, 'extra', 'should not exist'],
  ])('rejects %j at %s', (body, path, msg) => {
    const r = parsePatch(body);
    expect('errors' in r && r.errors[path]).toEqual(expect.stringContaining(msg));
  });

  it('start == end is invalid after the merge, including when only one side is sent', () => {
    expect(merge(DEFAULTS, { quietHours: { start: '07:00' } })).toEqual({ errors: { quietHours: 'start and end must differ' } });
    expect(merge(DEFAULTS, { quietHours: { start: '09:00', end: '09:00' } })).toHaveProperty('errors');
    expect(merge(DEFAULTS, { quietHours: { start: '08:00' } })).toEqual({ prefs: { ...DEFAULTS, quietHours: { enabled: true, start: '08:00', end: '07:00' } } });
  });

  it('merge is partial and samePrefs detects a no-op', () => {
    const r = merge(DEFAULTS, { types: { water_reminder: true } });
    expect('prefs' in r && r.prefs.types).toEqual({ ...DEFAULT_TYPES, water_reminder: true });
    expect(samePrefs(DEFAULTS, (merge(DEFAULTS, { types: { badge_earned: true } }) as { prefs: typeof DEFAULTS }).prefs)).toBe(true);
  });

  describe('isQuietAt (wall clock in the profile timezone)', () => {
    it('same-day window [start, end)', () => {
      expect(isQuietAt(q('13:00', '15:00'), ist('12:59'), 'Asia/Kolkata')).toBe(false);
      expect(isQuietAt(q('13:00', '15:00'), ist('13:00'), 'Asia/Kolkata')).toBe(true);
      expect(isQuietAt(q('13:00', '15:00'), ist('14:59'), 'Asia/Kolkata')).toBe(true);
      expect(isQuietAt(q('13:00', '15:00'), ist('15:00'), 'Asia/Kolkata')).toBe(false);
    });
    it('a window crossing midnight: 21:59 no, 22:00 yes, 06:59 yes, 07:00 no', () => {
      const w = q('22:00', '07:00');
      expect(isQuietAt(w, ist('21:59'), 'Asia/Kolkata')).toBe(false);
      expect(isQuietAt(w, ist('22:00'), 'Asia/Kolkata')).toBe(true);
      expect(isQuietAt(w, ist('00:00'), 'Asia/Kolkata')).toBe(true);
      expect(isQuietAt(w, ist('06:59'), 'Asia/Kolkata')).toBe(true);
      expect(isQuietAt(w, ist('07:00'), 'Asia/Kolkata')).toBe(false);
      expect(isQuietAt(q('22:00', '07:00', false), ist('23:00'), 'Asia/Kolkata')).toBe(false);
    });
    it('zone change: the same wall-clock window applies in the new zone', () => {
      const at = new Date('2026-10-29T17:00:00Z'); // 22:30 IST, 13:00 in New York (EDT)
      expect(isQuietAt(DEFAULTS.quietHours, at, 'Asia/Kolkata')).toBe(true);
      expect(isQuietAt(DEFAULTS.quietHours, at, 'America/New_York')).toBe(false);
      expect(isQuietAt(DEFAULTS.quietHours, new Date('2026-10-30T02:30:00Z'), 'America/New_York')).toBe(true); // 22:30 EDT
    });
    it('DST spring forward (America/New_York, 2026-03-08): the skipped 02:xx never occurs', () => {
      const w = q('02:00', '03:00');
      expect(isQuietAt(w, new Date('2026-03-08T06:59:00Z'), 'America/New_York')).toBe(false); // 01:59 EST
      expect(isQuietAt(w, new Date('2026-03-08T07:00:00Z'), 'America/New_York')).toBe(false); // 03:00 EDT
      // The 22:00-07:00 window is one hour shorter in real time that night.
      expect(isQuietAt(DEFAULTS.quietHours, new Date('2026-03-08T10:59:00Z'), 'America/New_York')).toBe(true); // 06:59 EDT
      expect(isQuietAt(DEFAULTS.quietHours, new Date('2026-03-08T11:00:00Z'), 'America/New_York')).toBe(false); // 07:00 EDT
    });
    it('DST fall back (America/New_York, 2026-11-01): the repeated 01:xx is quiet both times', () => {
      const w = q('01:00', '02:00');
      expect(isQuietAt(w, new Date('2026-11-01T05:30:00Z'), 'America/New_York')).toBe(true); // 01:30 EDT
      expect(isQuietAt(w, new Date('2026-11-01T06:30:00Z'), 'America/New_York')).toBe(true); // 01:30 EST
      expect(isQuietAt(w, new Date('2026-11-01T07:00:00Z'), 'America/New_York')).toBe(false); // 02:00 EST
    });
  });
});
