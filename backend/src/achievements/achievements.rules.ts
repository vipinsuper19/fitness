/**
 * Badges & challenges rules (docs/specs/badges-challenges.md). Pure functions
 * over "facts" loaded by AchievementsService; no I/O here.
 */
import { longestRun } from '../common/streak';
import { localDate, nextDate, prevDate, startOfLocalDay } from '../common/time';
import { balanceFor } from '../xp/xp.rules';

/** Bump when a badge is added or a rule changes; triggers the lazy backfill. Never revokes. */
export const CATALOG_VERSION = 1;
export const CHALLENGE_WEEKLY_XP_CAP = 150;
export const DEFAULT_WATER_GOAL_ML = 2000;
export const MAX_ACTIVE_CHALLENGES = 5;
export const DEFAULT_TIMEZONE = 'Asia/Kolkata';

export type BadgeFamily = 'workout' | 'streak' | 'nutrition' | 'hydration' | 'measurement' | 'level';

export interface CompletedSession {
  completedAt: Date;
  activeSeconds: number;
}

/** Everything the rules read. Client-dated entries are already filtered by `counts()`. */
export interface Facts {
  tz: string;
  today: string;
  sessions: CompletedSession[];
  completedSets: number;
  /** Net XP from workouts and their adjustments only (bonus XP excluded, so badges can't cascade). */
  coreXp: number;
  foodDates: string[];
  waterByDate: Map<string, number>;
  waterGoalMl: number;
  /**
   * The water goal in force on a local date: that day's `dailyWaterTargetMl` from
   * profile_target_history, else 2000 ml (no target yet, or water target unset that day).
   */
  waterGoalOn: (date: string) => number;
  measurementDates: string[];
}

export interface Progress {
  current: number;
  target: number;
}

export interface BadgeDef {
  code: string;
  title: string;
  description: string;
  family: BadgeFamily;
  bonusXp: number;
  progress(f: Facts): Progress;
}

/**
 * Backdating rule (profile timezone): a client-dated entry counts only if the
 * moment its date was last set (`dateSetAt`: createdAt, or dateChangedAt after a
 * date edit) falls on that date or the next local day:
 * startOfLocalDay(date) <= dateSetAt < startOfLocalDay(date + 2).
 * So future-dated entries never count (even once the day arrives), late entries
 * don't count, and a PATCH can't re-date an entry into a window where it would.
 * Checked on every evaluation; anything already awarded stays (never revoked).
 * Food logs use createdAt only: their date can't be PATCHed. If that changes, they
 * need `date_changed_at` like water logs and measurements.
 */
export function counts(date: string, dateSetAt: Date, today: string, tz: string): boolean {
  return date <= today && dateSetAt >= startOfLocalDay(date, tz) && dateSetAt < startOfLocalDay(nextDate(nextDate(date)), tz);
}

/** Monday (ISO week start) of a local date. */
export function weekStart(date: string): string {
  const dow = (new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7;
  let d = date;
  for (let i = 0; i < dow; i += 1) d = prevDate(d);
  return d;
}

export const workoutDays = (sessions: CompletedSession[], tz: string): Set<string> =>
  new Set(sessions.map((s) => localDate(s.completedAt, tz)));

const goalDays = (byDate: Map<string, number>, goal: number) => [...byDate].filter(([, ml]) => ml >= goal).map(([d]) => d);

const p = (current: number, target: number): Progress => ({ current: Math.max(0, Math.floor(current)), target });

const workouts = (code: string, n: number, bonusXp: number, title: string): BadgeDef => ({
  code, title, family: 'workout', bonusXp,
  description: n === 1 ? 'Complete your first workout.' : `Complete ${n} workouts.`,
  progress: (f) => p(f.sessions.length, n),
});
/** Streak badges use the longest run ever, so the release backfill finds streaks that already ended. */
const streak = (n: number, bonusXp: number): BadgeDef => ({
  code: `STREAK_${n}`, title: `${n}-day streak`, family: 'streak', bonusXp,
  description: `Complete a workout on ${n} consecutive days.`,
  progress: (f) => p(longestRun(workoutDays(f.sessions, f.tz)), n),
});

export const BADGES: readonly BadgeDef[] = [
  workouts('FIRST_WORKOUT', 1, 10, 'First workout'),
  workouts('WORKOUTS_10', 10, 25, '10 workouts'),
  workouts('WORKOUTS_50', 50, 50, '50 workouts'),
  workouts('WORKOUTS_100', 100, 100, '100 workouts'),
  streak(3, 15),
  streak(7, 30),
  streak(30, 100),
  { code: 'SETS_500', title: '500 sets', family: 'workout', bonusXp: 50, description: 'Complete 500 sets in finished workouts.', progress: (f) => p(f.completedSets, 500) },
  {
    code: 'HOUR_TOTAL_10', title: '10 hours', family: 'workout', bonusXp: 30, description: 'Train for 10 hours of active time in total.',
    progress: (f) => p(f.sessions.reduce((t, s) => t + s.activeSeconds, 0) / 60, 600),
  },
  {
    code: 'LONG_SESSION_60', title: 'Hour of power', family: 'workout', bonusXp: 20, description: 'Finish one workout with at least 60 minutes of active time.',
    progress: (f) => p(Math.max(0, ...f.sessions.map((s) => s.activeSeconds)) / 60, 60),
  },
  { code: 'FIRST_FOOD_LOG', title: 'First meal logged', family: 'nutrition', bonusXp: 5, description: 'Log your first food.', progress: (f) => p(f.foodDates.length ? 1 : 0, 1) },
  { code: 'FOOD_LOG_7_DAYS', title: 'Food diary week', family: 'nutrition', bonusXp: 20, description: 'Log food on 7 consecutive days.', progress: (f) => p(longestRun(f.foodDates), 7) },
  {
    code: 'HYDRATED_7', title: 'Well hydrated', family: 'hydration', bonusXp: 20, description: 'Reach your daily water goal on 7 days.',
    progress: (f) => p(goalDays(f.waterByDate, f.waterGoalMl).length, 7),
  },
  { code: 'FIRST_MEASUREMENT', title: 'First check-in', family: 'measurement', bonusXp: 5, description: 'Log your first body measurement.', progress: (f) => p(f.measurementDates.length ? 1 : 0, 1) },
  {
    code: 'MEASURE_4_WEEKS', title: 'Steady check-ins', family: 'measurement', bonusXp: 20, description: 'Log body measurements in 4 different weeks.',
    progress: (f) => p(new Set(f.measurementDates.map(weekStart)).size, 4),
  },
  { code: 'LEVEL_5', title: 'Level 5', family: 'level', bonusXp: 0, description: 'Reach level 5 from workout XP.', progress: (f) => p(balanceFor(f.coreXp).level, 5) },
];

export type ChallengeTemplateCode =
  | 'WEEKLY_WORKOUTS' | 'WEEKLY_ACTIVE_MINUTES' | 'WEEKLY_WATER_DAYS' | 'WEEKLY_FOOD_LOG_DAYS' | 'PERSONAL_WORKOUTS_N' | 'PERSONAL_STREAK_N';

export interface ChallengeTemplateDef {
  code: ChallengeTemplateCode;
  type: 'weekly' | 'personal';
  title: string;
  description: string;
  metric: string;
  unit: string;
  targets: number[];
  /** One entry per target; null = no end date. */
  deadlineDays: number[] | null;
  bonusXp: number;
}

export const CHALLENGE_TEMPLATES: readonly ChallengeTemplateDef[] = [
  { code: 'WEEKLY_WORKOUTS', type: 'weekly', title: 'Workouts this week', description: 'Complete the target number of workouts this week (Monday to Sunday).', metric: 'completed_workouts', unit: 'workouts', targets: [2, 3, 4, 5], deadlineDays: null, bonusXp: 30 },
  { code: 'WEEKLY_ACTIVE_MINUTES', type: 'weekly', title: 'Active minutes this week', description: 'Reach the target active minutes this week.', metric: 'active_minutes', unit: 'minutes', targets: [60, 90, 150], deadlineDays: null, bonusXp: 30 },
  { code: 'WEEKLY_WATER_DAYS', type: 'weekly', title: 'Hydration days', description: 'Reach your water goal on the target number of days this week.', metric: 'water_goal_days', unit: 'days', targets: [3, 5, 7], deadlineDays: null, bonusXp: 20 },
  { code: 'WEEKLY_FOOD_LOG_DAYS', type: 'weekly', title: 'Food diary days', description: 'Log food on the target number of days this week.', metric: 'food_log_days', unit: 'days', targets: [3, 5, 7], deadlineDays: null, bonusXp: 20 },
  { code: 'PERSONAL_WORKOUTS_N', type: 'personal', title: 'Workout goal', description: 'Complete the target number of workouts after joining, before the deadline (30, 60 or 90 days).', metric: 'completed_workouts', unit: 'workouts', targets: [10, 20, 30], deadlineDays: [30, 60, 90], bonusXp: 50 },
  { code: 'PERSONAL_STREAK_N', type: 'personal', title: 'Streak goal', description: 'Build a streak of the target length, counting only days from the day you join. No end date.', metric: 'streak_days', unit: 'days', targets: [7, 14, 30], deadlineDays: null, bonusXp: 50 },
];

export const templateByCode = (code: string): ChallengeTemplateDef | undefined => CHALLENGE_TEMPLATES.find((t) => t.code === code);

export interface ChallengeState {
  templateCode: string;
  target: number;
  windowStart: string;
  /** null = no end date. */
  windowEnd: string | null;
  joinDate: string;
  joinedAt: Date;
  timezone: string;
  waterGoalMl: number | null;
}

/** Current (uncapped) count for a challenge: only activity inside its window, up to today. */
export function challengeCount(c: ChallengeState, f: Facts, today: string): number {
  const upto = c.windowEnd !== null && c.windowEnd < today ? c.windowEnd : today;
  const inWindow = (d: string) => d >= c.windowStart && d <= upto;
  const sessionsIn = f.sessions.filter((s) => inWindow(localDate(s.completedAt, c.timezone)));
  switch (c.templateCode as ChallengeTemplateCode) {
    case 'WEEKLY_WORKOUTS':
      return sessionsIn.length;
    case 'WEEKLY_ACTIVE_MINUTES':
      return Math.floor(sessionsIn.reduce((t, s) => t + s.activeSeconds, 0) / 60);
    case 'WEEKLY_WATER_DAYS':
      // Each day is judged against its own dated target (c.waterGoalMl is the join-time value, display only).
      return [...f.waterByDate].filter(([d, ml]) => inWindow(d) && ml >= f.waterGoalOn(d)).length;
    case 'WEEKLY_FOOD_LOG_DAYS':
      return f.foodDates.filter(inWindow).length;
    case 'PERSONAL_WORKOUTS_N':
      // Only workouts finished after joining (not earlier the same day).
      return sessionsIn.filter((s) => s.completedAt >= c.joinedAt).length;
    case 'PERSONAL_STREAK_N': {
      // Only streak days on or after the join date: an existing streak can't complete it.
      const from = c.joinDate > c.windowStart ? c.joinDate : c.windowStart;
      return longestRun(workoutDays(f.sessions, c.timezone), from, upto);
    }
    default:
      return 0;
  }
}

/** Where an active challenge goes next. A null windowEnd never expires. */
export function nextStatus(count: number, target: number, windowEnd: string | null, today: string): 'completed' | 'expired' | 'active' {
  if (count >= target) return 'completed';
  if (windowEnd !== null && today > windowEnd) return 'expired';
  return 'active';
}

/** Challenge bonus after the weekly cap; may be 0. */
export function cappedAward(bonusXp: number, awardedThisWeek: number): number {
  return Math.max(0, Math.min(bonusXp, CHALLENGE_WEEKLY_XP_CAP - awardedThisWeek));
}
