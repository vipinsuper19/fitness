/**
 * Notification preferences (docs/specs/notification-preferences.md): catalog, defaults,
 * validation and the quiet-hours check. Pure; storage only — nothing sends notifications.
 */
export const NOTIFICATION_TYPES = [
  'workout_reminder', 'streak_reminder', 'water_reminder', 'food_log_reminder', 'challenge_updates', 'badge_earned', 'progression_suggestion',
] as const;
export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

export const DEFAULT_TYPES: Record<NotificationType, boolean> = {
  workout_reminder: true, streak_reminder: true, water_reminder: false, food_log_reminder: false,
  challenge_updates: true, badge_earned: true, progression_suggestion: true,
};

export interface QuietHours { enabled: boolean; start: string; end: string }
export interface Prefs { enabled: boolean; types: Record<NotificationType, boolean>; quietHours: QuietHours }
export interface PrefsView extends Prefs { timezone: string; updatedAt: string | null }

export const DEFAULTS: Prefs = { enabled: true, types: { ...DEFAULT_TYPES }, quietHours: { enabled: true, start: '22:00', end: '07:00' } };

export const LOCAL_TIME = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;
const isType = (k: string): k is NotificationType => (NOTIFICATION_TYPES as readonly string[]).includes(k);

/** Stored flags merged over current defaults; flags for removed types are ignored. */
export function effectiveTypes(stored: unknown): Record<NotificationType, boolean> {
  const out = { ...DEFAULT_TYPES };
  if (stored && typeof stored === 'object' && !Array.isArray(stored)) {
    for (const [k, v] of Object.entries(stored)) if (isType(k) && typeof v === 'boolean') out[k] = v;
  }
  return out;
}

/** Only the known types the user has set (removed types are dropped on write). */
export function storedTypes(stored: unknown): Partial<Record<NotificationType, boolean>> {
  const out: Partial<Record<NotificationType, boolean>> = {};
  if (stored && typeof stored === 'object' && !Array.isArray(stored)) {
    for (const [k, v] of Object.entries(stored)) if (isType(k) && typeof v === 'boolean') out[k] = v;
  }
  return out;
}

export interface PrefsPatch {
  enabled?: boolean;
  types?: Partial<Record<NotificationType, boolean>>;
  quietHours?: Partial<QuietHours>;
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Validates a PATCH body. Returns the patch, or field errors keyed by path (VALIDATION_FAILED). */
export function parsePatch(body: unknown): { patch: PrefsPatch } | { errors: Record<string, string> } {
  const errors: Record<string, string> = {};
  const patch: PrefsPatch = {};
  if (!isObj(body)) return { errors: { body: 'must be an object' } };
  const bool = (path: string, v: unknown): v is boolean => {
    if (typeof v === 'boolean') return true;
    errors[path] = v === null ? `${path} must not be null` : `${path} must be a boolean value`;
    return false;
  };
  for (const [k, v] of Object.entries(body)) {
    if (k === 'enabled') {
      if (bool('enabled', v)) patch.enabled = v;
    } else if (k === 'types') {
      if (!isObj(v)) { errors.types = v === null ? 'types must not be null' : 'types must be an object'; continue; }
      patch.types = {};
      for (const [t, f] of Object.entries(v)) {
        if (!isType(t)) errors[`types.${t}`] = 'unknown notification type';
        else if (bool(`types.${t}`, f)) patch.types[t] = f;
      }
    } else if (k === 'quietHours') {
      if (!isObj(v)) { errors.quietHours = v === null ? 'quietHours must not be null' : 'quietHours must be an object'; continue; }
      patch.quietHours = {};
      for (const [q, f] of Object.entries(v)) {
        const path = `quietHours.${q}`;
        if (q === 'enabled') { if (bool(path, f)) patch.quietHours.enabled = f; }
        else if (q === 'start' || q === 'end') {
          if (typeof f === 'string' && LOCAL_TIME.test(f)) patch.quietHours[q] = f;
          else errors[path] = f === null ? `${path} must not be null` : `${path} must be a local time HH:MM (00:00-23:59)`;
        } else errors[path] = `property ${q} should not exist`;
      }
    } else {
      errors[k] = `property ${k} should not exist`;
    }
  }
  return Object.keys(errors).length ? { errors } : { patch };
}

/** Applies a validated patch; `start == end` after the merge is an error on `quietHours`. */
export function merge(cur: Prefs, patch: PrefsPatch): { prefs: Prefs } | { errors: Record<string, string> } {
  const prefs: Prefs = {
    enabled: patch.enabled ?? cur.enabled,
    types: { ...cur.types, ...patch.types },
    quietHours: { ...cur.quietHours, ...patch.quietHours },
  };
  if (prefs.quietHours.start === prefs.quietHours.end) return { errors: { quietHours: 'start and end must differ' } };
  return { prefs };
}

export function samePrefs(a: Prefs, b: Prefs): boolean {
  return a.enabled === b.enabled
    && NOTIFICATION_TYPES.every((t) => a.types[t] === b.types[t])
    && a.quietHours.enabled === b.quietHours.enabled && a.quietHours.start === b.quietHours.start && a.quietHours.end === b.quietHours.end;
}

const minutes = (hhmm: string) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));

/** Local wall-clock HH:MM of `at` in `timeZone`. */
export function localWallTime(at: Date, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(at);
  const get = (t: string) => parts.find((p) => p.type === t)!.value;
  return `${get('hour')}:${get('minute')}`;
}

/**
 * Whether `at` falls in the quiet window `[start, end)`, by local wall clock in the profile
 * timezone at that moment (start > end crosses midnight). For the future sender; unused today.
 */
export function isQuietAt(q: QuietHours, at: Date, timeZone: string): boolean {
  if (!q.enabled) return false;
  const t = minutes(localWallTime(at, timeZone));
  const s = minutes(q.start);
  const e = minutes(q.end);
  return s < e ? t >= s && t < e : t >= s || t < e;
}
