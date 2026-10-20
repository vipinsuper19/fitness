import { balanceFor, workoutXp } from '../src/xp/xp.rules';

describe('XP rules', () => {
  it('awards base + sets + active time, capped, and nothing without completed sets', () => {
    expect(workoutXp(0, 3600)).toBe(0);
    expect(workoutXp(1, 0)).toBe(22);
    expect(workoutXp(10, 30 * 60)).toBe(20 + 20 + 6);
    expect(workoutXp(12, 299)).toBe(44);
    expect(workoutXp(30, 10 * 3600)).toBe(100);
    expect(workoutXp(500, 0)).toBe(80);
  });

  it('levels: 100 * level XP to reach the next level', () => {
    expect(balanceFor(0)).toEqual({ total: 0, level: 1, xpIntoLevel: 0, xpForNextLevel: 100 });
    expect(balanceFor(99)).toMatchObject({ level: 1, xpIntoLevel: 99 });
    expect(balanceFor(100)).toEqual({ total: 100, level: 2, xpIntoLevel: 0, xpForNextLevel: 200 });
    expect(balanceFor(650)).toEqual({ total: 650, level: 4, xpIntoLevel: 50, xpForNextLevel: 400 });
    expect(balanceFor(-5)).toMatchObject({ total: 0, level: 1 });
  });
});
