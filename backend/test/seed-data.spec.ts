import { EXERCISES } from '../prisma/seed-data/exercises';
import { FOODS } from '../prisma/seed-data/foods';
import { seedId } from '../prisma/seed-data/ids';

const UUID_V5 = /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('seed data', () => {
  it('has stable, unique UUIDv5 ids', () => {
    expect(seedId('food', 'roti')).toBe(seedId('food', 'roti'));
    expect(seedId('food', 'roti')).not.toBe(seedId('exercise', 'roti'));
    const ids = [...EXERCISES.map((e) => seedId('exercise', e.slug)), ...FOODS.map((f) => seedId('food', f.slug))];
    for (const id of ids) expect(id).toMatch(UUID_V5);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('has unique slugs and a usable catalog size', () => {
    expect(new Set(EXERCISES.map((e) => e.slug)).size).toBe(EXERCISES.length);
    expect(new Set(FOODS.map((f) => f.slug)).size).toBe(FOODS.length);
    expect(EXERCISES.length).toBeGreaterThanOrEqual(30);
    expect(FOODS.length).toBeGreaterThanOrEqual(40);
  });

  it('covers home (no equipment) workouts for every major area', () => {
    const home = EXERCISES.filter((e) => e.equipment.includes('none'));
    for (const muscle of ['chest', 'back', 'quads', 'glutes', 'core', 'cardio', 'full_body'] as const) {
      expect(home.some((e) => e.primaryMuscle === muscle)).toBe(true);
    }
  });

  it('uses metric or Indian household servings with plausible energy (4/4/9 rule within 15%)', () => {
    for (const f of FOODS) {
      expect(f.servingSize).toBeGreaterThan(0);
      expect(f.gramsPerServing).toBeGreaterThan(0);
      const estimate = 4 * f.proteinG + 4 * f.carbsG + 9 * f.fatG;
      expect({ slug: f.slug, ratio: Math.abs(estimate / f.energyKcal - 1) <= 0.15 }).toEqual({ slug: f.slug, ratio: true });
    }
    expect(FOODS.some((f) => f.servingUnit === 'katori')).toBe(true);
  });

  it('marks vegetarian status on every food (India-first diet filters)', () => {
    for (const f of FOODS) expect(typeof f.isVegetarian).toBe('boolean');
  });
});
