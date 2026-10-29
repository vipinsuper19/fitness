import {
  BADGES, CHALLENGE_TEMPLATES, cappedAward, challengeCount, ChallengeState, counts, Facts, nextStatus, weekStart,
} from '../src/achievements/achievements.rules';
import { currentStreak, longestRun } from '../src/common/streak';
import { startOfLocalDay } from '../src/common/time';

const tz = 'Asia/Kolkata';
const today = '2026-10-21'; // Wednesday
/** A session completed at local noon (IST) on `date`. */
const at = (date: string, hour = 12) => new Date(startOfLocalDay(date, tz).getTime() + hour * 3_600_000);
const days = (from: string, n: number) => Array.from({ length: n }, (_, i) => new Date(Date.parse(`${from}T00:00:00Z`) + i * 86_400_000).toISOString().slice(0, 10));
const facts = (over: Partial<Facts> = {}): Facts => ({
  tz, today, sessions: [], completedSets: 0, coreXp: 0, foodDates: [], waterByDate: new Map(), waterGoalOn: () => 2000, measurementDates: [], ...over,
});
const badge = (code: string) => BADGES.find((b) => b.code === code)!;
const earned = (code: string, f: Facts) => { const { current, target } = badge(code).progress(f); return current >= target; };

describe('streak helpers (shared with the progress summary)', () => {
  it('currentStreak keeps yesterday’s run alive', () => {
    expect(currentStreak(new Set(['2026-10-19', '2026-10-20']), today)).toBe(2);
    expect(currentStreak(new Set(['2026-10-19', '2026-10-20', today]), today)).toBe(3);
    expect(currentStreak(new Set(['2026-10-18']), today)).toBe(0);
  });
  it('longestRun respects [from, to] and month edges', () => {
    expect(longestRun(['2026-09-29', '2026-09-30', '2026-10-01', '2026-10-03'])).toBe(3);
    expect(longestRun(days('2026-10-01', 10), '2026-10-05')).toBe(6);
    expect(longestRun(days('2026-10-01', 10), '2026-10-05', '2026-10-06')).toBe(2);
    expect(longestRun([])).toBe(0);
  });
});

describe('time rules', () => {
  it('weekStart is Monday', () => {
    expect(weekStart('2026-10-19')).toBe('2026-10-19');
    expect(weekStart('2026-10-25')).toBe('2026-10-19');
    expect(weekStart('2026-10-26')).toBe('2026-10-26');
    expect(weekStart('2026-01-01')).toBe('2025-12-29');
  });
  it('backdating: same or next local day counts, later or future does not', () => {
    const d = '2026-10-19';
    expect(counts(d, at(d, 23), today, tz)).toBe(true);
    expect(counts(d, at('2026-10-20', 23.9), today, tz)).toBe(true); // 23:54 IST next day
    expect(counts(d, startOfLocalDay('2026-10-21', tz), today, tz)).toBe(false); // 00:00 IST two days later
    expect(counts('2026-10-22', at(today), today, tz)).toBe(false); // future date
    expect(counts('2026-10-20', at('2026-10-19', 23), today, tz)).toBe(false); // logged the day before its date: never counts, even once the day arrives
  });
});

describe('badge rules (boundaries)', () => {
  it('workout counts', () => {
    const s = (n: number) => facts({ sessions: Array.from({ length: n }, () => ({ completedAt: at('2026-10-01'), activeSeconds: 600 })) });
    expect(earned('FIRST_WORKOUT', s(0))).toBe(false);
    expect(earned('FIRST_WORKOUT', s(1))).toBe(true);
    expect(earned('WORKOUTS_10', s(9))).toBe(false);
    expect(earned('WORKOUTS_10', s(10))).toBe(true);
  });
  it('streaks use local days and the longest run', () => {
    const f = (dates: string[]) => facts({ sessions: dates.map((d) => ({ completedAt: at(d), activeSeconds: 60 })) });
    expect(earned('STREAK_7', f(days('2026-10-01', 6)))).toBe(false);
    expect(earned('STREAK_7', f(days('2026-10-01', 7)))).toBe(true); // ended long ago: still found by backfill
    // 00:30 IST on the 2nd is still the 2nd locally (UTC says the 1st).
    expect(earned('STREAK_3', facts({ sessions: [at('2026-10-01'), new Date('2026-10-01T19:00:00Z'), at('2026-10-03')].map((completedAt) => ({ completedAt, activeSeconds: 1 })) }))).toBe(true);
  });
  it('time and set badges', () => {
    expect(earned('LONG_SESSION_60', facts({ sessions: [{ completedAt: at(today), activeSeconds: 3599 }] }))).toBe(false);
    expect(earned('LONG_SESSION_60', facts({ sessions: [{ completedAt: at(today), activeSeconds: 3600 }] }))).toBe(true);
    expect(earned('HOUR_TOTAL_10', facts({ sessions: Array.from({ length: 10 }, () => ({ completedAt: at(today), activeSeconds: 3600 })) }))).toBe(true);
    expect(earned('SETS_500', facts({ completedSets: 499 }))).toBe(false);
    expect(earned('SETS_500', facts({ completedSets: 500 }))).toBe(true);
  });
  it('nutrition, hydration (user goal), measurements and level', () => {
    expect(earned('FOOD_LOG_7_DAYS', facts({ foodDates: [...days('2026-10-01', 6), '2026-10-08'] }))).toBe(false);
    expect(earned('FOOD_LOG_7_DAYS', facts({ foodDates: days('2026-10-01', 7) }))).toBe(true);
    const water = new Map(days('2026-10-01', 7).map((d) => [d, 2500]));
    expect(earned('HYDRATED_7', facts({ waterByDate: water }))).toBe(true);
    expect(earned('HYDRATED_7', facts({ waterByDate: water, waterGoalOn: () => 3000 }))).toBe(false);
    // Goal raised mid-streak (from 10-04): days 1-3 judged at 2000, days 4-7 at 3000.
    const raised = (d: string) => (d >= '2026-10-04' ? 3000 : 2000);
    expect(earned('HYDRATED_7', facts({ waterByDate: water, waterGoalOn: raised }))).toBe(false);
    expect(badge('HYDRATED_7').progress(facts({ waterByDate: water, waterGoalOn: raised })).current).toBe(3);
    const met = new Map(days('2026-10-01', 7).map((d) => [d, raised(d)]));
    expect(earned('HYDRATED_7', facts({ waterByDate: met, waterGoalOn: raised }))).toBe(true);
    expect(earned('MEASURE_4_WEEKS', facts({ measurementDates: ['2026-10-05', '2026-10-11', '2026-10-12', '2026-10-19'] }))).toBe(false); // 3 weeks
    expect(earned('MEASURE_4_WEEKS', facts({ measurementDates: ['2026-09-28', '2026-10-05', '2026-10-12', '2026-10-19'] }))).toBe(true);
    expect(earned('LEVEL_5', facts({ coreXp: 999 }))).toBe(false); // level 4
    expect(earned('LEVEL_5', facts({ coreXp: 1000 }))).toBe(true);
  });
  it('has unique codes and non-negative XP', () => {
    expect(new Set(BADGES.map((b) => b.code)).size).toBe(BADGES.length);
    expect(BADGES.every((b) => b.bonusXp >= 0)).toBe(true);
    for (const t of CHALLENGE_TEMPLATES) if (t.deadlineDays) expect(t.deadlineDays).toHaveLength(t.targets.length);
  });
});

describe('challenge rules', () => {
  const base = (over: Partial<ChallengeState>): ChallengeState => ({
    templateCode: 'WEEKLY_WORKOUTS', target: 3, windowStart: '2026-10-19', windowEnd: '2026-10-25', joinDate: today, joinedAt: at(today, 10), timezone: tz, waterGoalMl: null, ...over,
  });
  const sessions = (dates: string[], hour = 12) => dates.map((d) => ({ completedAt: at(d, hour), activeSeconds: 1800 }));

  it('weekly counts only the window, including days before joining in that week', () => {
    const f = facts({ sessions: sessions(['2026-10-18', '2026-10-19', '2026-10-20', today]) });
    expect(challengeCount(base({}), f, today)).toBe(3);
    expect(challengeCount(base({ templateCode: 'WEEKLY_ACTIVE_MINUTES', target: 60 }), f, today)).toBe(90);
  });
  it('water days use each day\'s own goal (default 2000), not the goal captured at join', () => {
    const f = facts({ waterByDate: new Map([['2026-10-19', 2000], ['2026-10-20', 2400], ['2026-10-18', 9000]]) });
    expect(challengeCount(base({ templateCode: 'WEEKLY_WATER_DAYS' }), f, today)).toBe(2);
    expect(challengeCount(base({ templateCode: 'WEEKLY_WATER_DAYS', waterGoalMl: 2200 }), f, today)).toBe(2);
    expect(challengeCount(base({ templateCode: 'WEEKLY_WATER_DAYS' }), { ...f, waterGoalOn: () => 2200 }, today)).toBe(1);
  });
  it('water goal changed mid-week: each day is judged against its own goal', () => {
    // Mon 19 and Tue 20 at 2000; from Wed 21 the goal is 3000.
    const goalOn = (d: string) => (d >= '2026-10-21' ? 3000 : 2000);
    const w = new Map([['2026-10-19', 2500], ['2026-10-20', 2000], ['2026-10-21', 2500]]);
    expect(challengeCount(base({ templateCode: 'WEEKLY_WATER_DAYS', waterGoalMl: 3000 }), facts({ waterByDate: w, waterGoalOn: goalOn }), today)).toBe(2);
    w.set('2026-10-21', 3000);
    expect(challengeCount(base({ templateCode: 'WEEKLY_WATER_DAYS', waterGoalMl: 3000 }), facts({ waterByDate: w, waterGoalOn: goalOn }), today)).toBe(3);
  });
  it('personal workouts count only after joining (not earlier the same day)', () => {
    const f = facts({ sessions: [...sessions([today], 8), ...sessions([today], 18)] });
    expect(challengeCount(base({ templateCode: 'PERSONAL_WORKOUTS_N', windowStart: today, windowEnd: null }), f, today)).toBe(1);
  });
  it('streak challenge: a pre-existing streak cannot complete it; only days from join count', () => {
    const f = facts({ sessions: sessions(days('2026-10-08', 14)) }); // 14-day streak ending today
    const joinedToday = base({ templateCode: 'PERSONAL_STREAK_N', target: 7, windowStart: today, windowEnd: null, joinDate: today });
    expect(challengeCount(joinedToday, f, today)).toBe(1);
    expect(nextStatus(1, 7, null, today)).toBe('active');
    const joined6DaysAgo = { ...joinedToday, windowStart: '2026-10-15', joinDate: '2026-10-15' };
    expect(challengeCount(joined6DaysAgo, f, today)).toBe(7);
  });
  it('a null end date never expires; a past end date expires unless reached', () => {
    expect(nextStatus(0, 3, null, '2099-01-01')).toBe('active');
    expect(nextStatus(2, 3, '2026-10-18', today)).toBe('expired');
    expect(nextStatus(3, 3, '2026-10-18', today)).toBe('completed');
    expect(nextStatus(2, 3, today, today)).toBe('active'); // last day still open
  });
  it('a finished window only counts activity inside it', () => {
    const f = facts({ sessions: sessions(['2026-10-12', '2026-10-13', '2026-10-20']) });
    expect(challengeCount(base({ windowStart: '2026-10-12', windowEnd: '2026-10-18' }), f, today)).toBe(2);
  });
  it('caps challenge XP at 150 per week, down to 0', () => {
    expect(cappedAward(30, 0)).toBe(30);
    expect(cappedAward(30, 130)).toBe(20);
    expect(cappedAward(30, 150)).toBe(0);
    expect(cappedAward(30, 200)).toBe(0);
  });
});
