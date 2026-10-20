/**
 * Rule-based plan generator (no AI provider in the MVP).
 *
 * A pure function of the request and the catalog: the same input always
 * gives the same draft. Rules, in order:
 * 1. Split by days per week: 1-3 full body (A/B alternating), 4 upper/lower,
 *    5 push/pull/legs + upper/lower, 6 push/pull/legs twice, 7 adds a
 *    light conditioning day.
 * 2. Days are spread over the ISO week (1 = Monday) to leave rest days.
 * 3. Exercises per session from the time budget: 10 minutes of warm-up and
 *    cool-down, then about 8 minutes per exercise, clamped to 3..8.
 * 4. Each slot takes an exercise for that muscle the user can do: all of
 *    its equipment is available (bodyweight is always available) and its
 *    difficulty is at or below the user's level. Closer difficulty first,
 *    then equipment-loaded moves when the user has equipment, then slug.
 *    Repeated workouts (A/B, second push day) rotate through candidates.
 * 5. Sets, reps and rest follow the goal; timed and cardio moves get
 *    durations instead of reps. Target weights are left to the user.
 */

export type Goal = 'lose_weight' | 'gain_muscle' | 'maintain' | 'improve_endurance' | 'workouts_per_week';
export type Level = 'beginner' | 'intermediate' | 'advanced';
export type Muscle =
  | 'chest' | 'back' | 'shoulders' | 'biceps' | 'triceps' | 'forearms' | 'core'
  | 'quads' | 'hamstrings' | 'glutes' | 'calves' | 'full_body' | 'cardio';
export type Equip =
  | 'none' | 'dumbbell' | 'barbell' | 'kettlebell' | 'machine' | 'cable'
  | 'resistance_band' | 'bench' | 'pull_up_bar' | 'other';
export type Tracking = 'reps_weight' | 'reps' | 'duration' | 'distance_duration';

export interface CatalogExercise {
  id: string;
  slug: string;
  name: string;
  primaryMuscle: Muscle;
  secondaryMuscles: Muscle[];
  equipment: Equip[];
  trackingType: Tracking;
  difficulty: Level;
}

export interface GenerateRequest {
  goal: Goal;
  experienceLevel: Level;
  daysPerWeek: number;
  sessionMinutes: number;
  equipment?: Equip[];
  weeks?: number;
}

export interface DraftExercise {
  exerciseId: string;
  order: number;
  sets: number;
  repsMin: number | null;
  repsMax: number | null;
  durationSeconds: number | null;
  targetWeightKg: number | null;
  restSeconds: number;
  notes: string | null;
}

export interface DraftWorkout {
  dayOfWeek: number;
  name: string;
  exercises: DraftExercise[];
}

export interface WorkoutPlanDraft {
  name: string;
  weeks: number;
  generator: 'rule_based';
  rationale: string;
  workouts: DraftWorkout[];
}

type Template = 'full_a' | 'full_b' | 'upper' | 'lower' | 'push' | 'pull' | 'legs' | 'conditioning';

const SLOTS: Record<Template, Muscle[]> = {
  full_a: ['quads', 'chest', 'back', 'glutes', 'shoulders', 'core', 'hamstrings', 'cardio'],
  full_b: ['hamstrings', 'back', 'chest', 'quads', 'triceps', 'core', 'calves', 'full_body'],
  upper: ['chest', 'back', 'shoulders', 'back', 'triceps', 'biceps', 'core', 'chest'],
  lower: ['quads', 'hamstrings', 'glutes', 'calves', 'core', 'quads', 'glutes', 'core'],
  push: ['chest', 'shoulders', 'triceps', 'chest', 'core', 'shoulders', 'triceps', 'core'],
  pull: ['back', 'back', 'biceps', 'hamstrings', 'core', 'back', 'forearms', 'core'],
  legs: ['quads', 'hamstrings', 'glutes', 'calves', 'core', 'quads', 'hamstrings', 'core'],
  conditioning: ['cardio', 'full_body', 'core', 'cardio', 'full_body', 'core', 'cardio', 'core'],
};

const NAMES: Record<Template, string> = {
  full_a: 'Full body A',
  full_b: 'Full body B',
  upper: 'Upper body',
  lower: 'Lower body',
  push: 'Push',
  pull: 'Pull',
  legs: 'Legs',
  conditioning: 'Conditioning',
};

const SPLITS: Record<number, Template[]> = {
  1: ['full_a'],
  2: ['full_a', 'full_b'],
  3: ['full_a', 'full_b', 'full_a'],
  4: ['upper', 'lower', 'upper', 'lower'],
  5: ['push', 'pull', 'legs', 'upper', 'lower'],
  6: ['push', 'pull', 'legs', 'push', 'pull', 'legs'],
  7: ['push', 'pull', 'legs', 'conditioning', 'push', 'pull', 'legs'],
};

const SPLIT_LABEL: Record<number, string> = {
  1: 'full body',
  2: 'full body',
  3: 'full body',
  4: 'upper/lower',
  5: 'push/pull/legs + upper/lower',
  6: 'push/pull/legs',
  7: 'push/pull/legs + conditioning',
};

/** ISO weekdays, spread so rest days fall between sessions where possible. */
const DAYS: Record<number, number[]> = {
  1: [3],
  2: [2, 5],
  3: [1, 3, 5],
  4: [1, 2, 4, 5],
  5: [1, 2, 3, 5, 6],
  6: [1, 2, 3, 4, 5, 6],
  7: [1, 2, 3, 4, 5, 6, 7],
};

const LEVEL_RANK: Record<Level, number> = { beginner: 0, intermediate: 1, advanced: 2 };

interface Prescription {
  sets: number;
  repsMin: number;
  repsMax: number;
  restSeconds: number;
}

const PRESCRIPTION: Record<Goal, Prescription> = {
  gain_muscle: { sets: 3, repsMin: 8, repsMax: 12, restSeconds: 90 },
  lose_weight: { sets: 3, repsMin: 12, repsMax: 15, restSeconds: 45 },
  maintain: { sets: 3, repsMin: 10, repsMax: 12, restSeconds: 60 },
  improve_endurance: { sets: 2, repsMin: 15, repsMax: 20, restSeconds: 30 },
  workouts_per_week: { sets: 3, repsMin: 10, repsMax: 12, restSeconds: 60 },
};

const HOLD_SECONDS: Record<Level, number> = { beginner: 30, intermediate: 45, advanced: 60 };

const GOAL_LABEL: Record<Goal, string> = {
  lose_weight: 'fat loss',
  gain_muscle: 'muscle gain',
  maintain: 'maintenance',
  improve_endurance: 'endurance',
  workouts_per_week: 'consistency',
};

export function exercisesPerSession(minutes: number): number {
  return Math.min(8, Math.max(3, Math.floor((minutes - 10) / 8)));
}

function prescribe(e: CatalogExercise, goal: Goal, level: Level, minutes: number): Omit<DraftExercise, 'exerciseId' | 'order'> {
  const p = PRESCRIPTION[goal];
  const sets = level === 'beginner' ? p.sets : Math.min(p.sets + 1, 5);
  if (e.trackingType === 'distance_duration') {
    // Steady cardio: one block, longer for fat-loss and endurance goals.
    const long = goal === 'lose_weight' || goal === 'improve_endurance';
    const durationSeconds = Math.min(long ? 20 * 60 : 10 * 60, Math.max(5 * 60, (minutes - 10) * 30));
    return { sets: 1, repsMin: null, repsMax: null, durationSeconds, targetWeightKg: null, restSeconds: 0, notes: 'Easy, conversational pace.' };
  }
  if (e.trackingType === 'duration') {
    return { sets, repsMin: null, repsMax: null, durationSeconds: HOLD_SECONDS[level], targetWeightKg: null, restSeconds: Math.min(p.restSeconds, 60), notes: null };
  }
  const notes = e.trackingType === 'reps_weight' ? 'Pick a weight that leaves 1-2 reps in reserve.' : null;
  return { sets, repsMin: p.repsMin, repsMax: p.repsMax, durationSeconds: null, targetWeightKg: null, restSeconds: p.restSeconds, notes };
}

export function generatePlan(req: GenerateRequest, catalog: readonly CatalogExercise[]): WorkoutPlanDraft {
  const available = new Set<Equip>(['none', ...(req.equipment ?? [])]);
  const hasKit = available.size > 1;
  const level = LEVEL_RANK[req.experienceLevel];
  const usable = catalog
    .filter((e) => e.equipment.every((x) => available.has(x)) && LEVEL_RANK[e.difficulty] <= level)
    .slice()
    .sort((a, b) => (a.slug < b.slug ? -1 : a.slug > b.slug ? 1 : 0));

  const rank = (e: CatalogExercise) => {
    const closeness = level - LEVEL_RANK[e.difficulty];
    const loaded = hasKit && !e.equipment.includes('none') ? 0 : 1;
    return closeness * 2 + loaded;
  };
  const candidatesFor = (m: Muscle) => {
    const primary = usable.filter((e) => e.primaryMuscle === m);
    const pool = primary.length ? primary : usable.filter((e) => e.secondaryMuscles.includes(m));
    return pool.slice().sort((a, b) => rank(a) - rank(b) || (a.slug < b.slug ? -1 : 1));
  };

  const perSession = exercisesPerSession(req.sessionMinutes);
  const split = SPLITS[req.daysPerWeek]!;
  const days = DAYS[req.daysPerWeek]!;
  const seen = new Map<Template, number>();

  const workouts: DraftWorkout[] = split.map((template, i) => {
    const variant = seen.get(template) ?? 0;
    seen.set(template, variant + 1);
    const slotUse = new Map<Muscle, number>();
    const picked: CatalogExercise[] = [];
    for (const muscle of SLOTS[template]) {
      if (picked.length >= perSession) break;
      const nth = slotUse.get(muscle) ?? 0;
      slotUse.set(muscle, nth + 1);
      const pool = candidatesFor(muscle).filter((e) => !picked.includes(e));
      if (!pool.length) continue;
      picked.push(pool[(variant + nth) % pool.length]!);
    }
    // Small catalogs (bodyweight only): top up from core and full-body moves.
    for (const muscle of ['core', 'full_body', 'cardio', 'glutes'] as Muscle[]) {
      if (picked.length >= Math.min(3, perSession)) break;
      const extra = candidatesFor(muscle).find((e) => !picked.includes(e));
      if (extra) picked.push(extra);
    }
    const name = variant > 0 && template !== 'full_a' && template !== 'full_b' ? `${NAMES[template]} ${variant + 1}` : NAMES[template];
    return {
      dayOfWeek: days[i]!,
      name,
      exercises: picked.map((e, order) => ({ exerciseId: e.id, order, ...prescribe(e, req.goal, req.experienceLevel, req.sessionMinutes) })),
    };
  }).filter((w) => w.exercises.length > 0);

  const kit = hasKit ? [...available].filter((x) => x !== 'none').join(', ').replace(/_/g, ' ') : 'bodyweight only';
  const levelName = req.experienceLevel[0]!.toUpperCase() + req.experienceLevel.slice(1);
  return {
    name: `${req.daysPerWeek}-day ${SPLIT_LABEL[req.daysPerWeek]} (${req.experienceLevel})`,
    weeks: req.weeks ?? 4,
    generator: 'rule_based',
    rationale:
      `${levelName} ${SPLIT_LABEL[req.daysPerWeek]} split for ${GOAL_LABEL[req.goal]}, ${req.daysPerWeek} day(s) a week, ` +
      `about ${req.sessionMinutes} minutes a session (${perSession} exercises after a 10-minute warm-up and cool-down). ` +
      `Equipment: ${kit}. Built by fixed rules, not AI. Add weight when you hit the top of the rep range on every set.`,
    workouts,
  };
}
