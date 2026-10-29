/**
 * Seed and hot-path queries for the index review (docs/perf/index-review.md, test/index-review.int-spec.ts).
 * Plain strings with no imports, so the same SQL can be run with psql against a scratch Postgres.
 * One "main" user has a year of everything; 1000 other users have a month. Rows are inserted in
 * time order across users, as production writes them, so a user's rows are spread over the heap.
 */
export const OTHERS = 1000;
const id = (ns: string, n: number | string) => `('b0e1b0e1-0000-4000-${ns}-' || lpad(to_hex(${n}), 12, '0'))::uuid`;
const lit = (ns: string, n: number) => `b0e1b0e1-0000-4000-${ns}-${n.toString(16).padStart(12, '0')}`;
/** The main user (n = 1); others are 2..OTHERS+1. */
export const U = lit('8000', 1);
/** Another user (no year of data), deleted inside a rolled-back transaction to time DELETE /me. */
export const OTHER = lit('8000', 2);
export const PLAN = lit('8100', 1);
/** OTHER's plan version and its first planned workout (ids follow the seed's numbering). */
const OTHER_VERSION = lit('8200', 4);
const OTHER_WORKOUT = lit('8300', 12);
export const FOOD = lit('9000', 1);
export const EX = (n: number) => lit('a000', n);
export const EMAIL_LIKE = `email LIKE 'index-review-%'`;
const users = `(SELECT id, row_number() OVER (ORDER BY id) n FROM users WHERE ${EMAIL_LIKE})`;
const TODAY = `DATE '2026-10-29'`;
const NOW = `TIMESTAMPTZ '2026-10-29 06:00Z'`;

export const SEED: string[] = [
  `INSERT INTO users (id, email, password_hash, updated_at)
   SELECT ${id('8000', 'g')}, 'index-review-' || g || '@example.test', 'x', now() FROM generate_series(1, ${OTHERS + 1}) g`,
  `INSERT INTO user_profiles (user_id, timezone, updated_at) SELECT id, 'Asia/Kolkata', now() FROM ${users} u`,
  `INSERT INTO food_items (id, name, serving_size, serving_unit, energy_kcal, protein_g, carbs_g, fat_g, source)
   VALUES ('${FOOD}', 'Index review dal', 1, 'katori', 150, 9, 20, 4, 'catalog')`,
  `INSERT INTO exercises (id, slug, name, primary_muscle, tracking_type, difficulty)
   SELECT ${id('a000', 'g')}, 'index-review-' || g, 'Index review ' || g, 'full_body', 'reps', 'advanced' FROM generate_series(1, 20) g`,
  // Plans: the main user has 3 versions (a new one every ~4 months); everyone else 1. 3 planned workouts x 5 exercises each.
  `INSERT INTO workout_plans (id, user_id, source, current_version, created_at, updated_at)
   SELECT ${id('8100', 'u.n')}, u.id, 'rule_based', CASE WHEN u.n = 1 THEN 3 ELSE 1 END, ${NOW} - interval '1 year', now() FROM ${users} u`,
  `INSERT INTO workout_plan_versions (id, plan_id, version, name, weeks, created_at)
   SELECT ${id('8200', '(u.n - 1) * 3 + v')}, ${id('8100', 'u.n')}, v, 'Plan v' || v, 12, ${NOW} - make_interval(days => 365 - (v - 1) * 120)
   FROM ${users} u, generate_series(1, 3) v WHERE u.n = 1 OR v = 1`,
  `INSERT INTO planned_workouts (id, plan_version_id, day_of_week, name, "order")
   SELECT ${id('8300', '((u.n - 1) * 3 + v) * 3 + d')}, ${id('8200', '(u.n - 1) * 3 + v')}, d * 2 + 1, 'Day ' || d, d
   FROM ${users} u, generate_series(1, 3) v, generate_series(0, 2) d WHERE u.n = 1 OR v = 1`,
  `INSERT INTO planned_exercises (id, planned_workout_id, exercise_id, "order", sets, reps_min, reps_max)
   SELECT gen_random_uuid(), pw.id, ${id('a000', '1 + (pw."order" * 5 + e) % 20')}, e, 3, 8, 12
   FROM planned_workouts pw JOIN workout_plan_versions v ON v.id = pw.plan_version_id JOIN workout_plans p ON p.id = v.plan_id
   JOIN users x ON x.id = p.user_id AND x.${EMAIL_LIKE}, generate_series(0, 4) e`,
  // Sessions: main user 180 completed over a year (every ~2 days, on the version current at the time); others 10 in the last month.
  `INSERT INTO workout_sessions (id, user_id, plan_version_id, planned_workout_id, status, started_at, completed_at, active_seconds, updated_at)
   SELECT gen_random_uuid(), u.id, ${id('8200', '(u.n - 1) * 3 + ver')}, ${id('8300', '((u.n - 1) * 3 + ver) * 3 + s % 3')},
          'completed', ${NOW} - make_interval(days => s * 2, hours => 1), ${NOW} - make_interval(days => s * 2), 3000, now()
   FROM ${users} u, generate_series(1, 180) s, LATERAL (SELECT CASE WHEN u.n > 1 THEN 1 WHEN s > 120 THEN 1 WHEN s > 60 THEN 2 ELSE 3 END ver) l
   WHERE u.n = 1 OR s <= 15 ORDER BY s DESC, u.n`,
  `INSERT INTO exercise_set_logs (id, session_id, user_id, client_set_id, exercise_id, set_index, reps, weight_kg, completed, logged_at, updated_at)
   SELECT gen_random_uuid(), w.id, w.user_id, gen_random_uuid(), pe.exercise_id, pe."order" * 3 + k, 10, 40 + k, true,
          w.completed_at - make_interval(mins => 50 - pe."order" * 10 - k), now()
   FROM workout_sessions w JOIN users x ON x.id = w.user_id AND x.${EMAIL_LIKE}
   JOIN planned_exercises pe ON pe.planned_workout_id = w.planned_workout_id, generate_series(0, 2) k
   ORDER BY w.completed_at, w.id, pe."order", k`,
  // Diary: 4 food and 4 water entries a day (main user a year, others a month).
  `INSERT INTO food_logs (id, user_id, food_id, date, meal_type, servings, energy_kcal, protein_g, carbs_g, fat_g, created_at, updated_at)
   SELECT gen_random_uuid(), u.id, '${FOOD}', ${TODAY} - d, (ARRAY['breakfast','lunch','dinner','snack']::"MealType"[])[m],
          1, 150, 9, 20, 4, (${TODAY} - d) + make_interval(hours => 2 + m * 4), now()
   FROM ${users} u, generate_series(0, 364) d, generate_series(1, 4) m WHERE u.n = 1 OR d < 30 ORDER BY d DESC, m, u.n`,
  `INSERT INTO water_logs (id, user_id, date, amount_ml, created_at, updated_at)
   SELECT gen_random_uuid(), u.id, ${TODAY} - d, 250, (${TODAY} - d) + make_interval(hours => 2 + m * 3), now()
   FROM ${users} u, generate_series(0, 364) d, generate_series(1, 4) m WHERE u.n = 1 OR d < 30 ORDER BY d DESC, m, u.n`,
  `INSERT INTO profile_target_history (user_id, effective_from, daily_calorie_target_kcal, updated_at)
   SELECT u.id, ${TODAY} - i * 30, 2000, now() FROM ${users} u, generate_series(0, 11) i WHERE u.n = 1 OR i = 0 ORDER BY i DESC, u.n`,
  // XP: one award per completed session, plus badges/challenges/adjustments (main ~400, others ~25).
  `INSERT INTO xp_transactions (id, user_id, source_type, source_id, amount, reason, created_at)
   SELECT gen_random_uuid(), w.user_id, 'workout_session', w.id, 50, 'session', w.completed_at
   FROM workout_sessions w JOIN users x ON x.id = w.user_id AND x.${EMAIL_LIKE} ORDER BY w.completed_at`,
  `INSERT INTO xp_transactions (id, user_id, source_type, source_id, amount, reason, created_at)
   SELECT gen_random_uuid(), u.id, (ARRAY['badge','challenge','adjustment']::"XpSourceType"[])[1 + i % 3], gen_random_uuid(), 20, 'other',
          ${NOW} - make_interval(hours => i * 40)
   FROM ${users} u, generate_series(1, 220) i WHERE u.n = 1 OR i <= 10 ORDER BY i DESC, u.n`,
  `INSERT INTO user_badges (id, user_id, badge_code, earned_at, catalog_version)
   SELECT gen_random_uuid(), u.id, 'badge_' || b, ${NOW} - make_interval(days => 365 - b * 15), 1
   FROM ${users} u, generate_series(1, 24) b WHERE u.n = 1 OR b <= 3 ORDER BY b, u.n`,
  // Challenges: a weekly challenge every week for the main user (the last one active), 4 for others.
  `INSERT INTO user_challenges (id, user_id, template_code, type, target, window_start, window_end, timezone, status, progress, joined_at, join_date, completed_at, updated_at)
   SELECT gen_random_uuid(), u.id, 'weekly_' || (w % 3), 'weekly', 3, ${TODAY} - w * 7, ${TODAY} - w * 7 + 6, 'Asia/Kolkata',
          (CASE WHEN w = 0 THEN 'active' WHEN w % 4 = 0 THEN 'expired' ELSE 'completed' END)::"ChallengeStatus", 3,
          ${NOW} - make_interval(days => w * 7), ${TODAY} - w * 7, CASE WHEN w > 0 AND w % 4 <> 0 THEN ${NOW} - make_interval(days => w * 7 - 5) END, now()
   FROM ${users} u, generate_series(0, 51) w WHERE u.n = 1 OR w < 4 ORDER BY w DESC, u.n`,
  // Idempotency keys (24h TTL, not yet swept): main 300, others 20.
  `INSERT INTO idempotency_keys (id, scope, key, user_id, method, path, request_hash, status, response_status, created_at, expires_at)
   SELECT gen_random_uuid(), 'sets', gen_random_uuid(), u.id, 'POST', '/x', repeat('a', 64), 'completed', 201, ${NOW} - make_interval(mins => i), ${NOW} + interval '1 day'
   FROM ${users} u, generate_series(1, 300) i WHERE u.n = 1 OR i <= 20 ORDER BY i DESC, u.n`,
];

export const TABLES = [
  'users', 'user_profiles', 'workout_plans', 'workout_plan_versions', 'planned_workouts', 'planned_exercises', 'workout_sessions',
  'exercise_set_logs', 'food_logs', 'water_logs', 'profile_target_history', 'xp_transactions', 'user_badges', 'user_challenges',
  'idempotency_keys',
];

export const CLEANUP = [
  `DELETE FROM users WHERE ${EMAIL_LIKE}`,
  `DELETE FROM exercises WHERE slug LIKE 'index-review-%'`,
  `DELETE FROM food_items WHERE id = '${FOOD}'`,
];

const LAST21 = `(SELECT id FROM workout_sessions WHERE user_id = '${U}' AND status = 'completed' AND completed_at IS NOT NULL ORDER BY completed_at DESC, id DESC LIMIT 21)`;
const LATEST = `(SELECT id FROM workout_sessions WHERE user_id = '${U}' AND status = 'completed' ORDER BY completed_at DESC LIMIT 1)`;
const VERSIONS = `(SELECT id FROM workout_plan_versions WHERE plan_id = '${PLAN}')`;

/** Hot paths, same predicates and order as the services' Prisma / raw SQL, for the main user. */
export const QUERIES: Record<string, string> = {
  // Today dashboard (GET /progress/summary for today + streak, GET /nutrition/daily).
  'today: profile timezone': `SELECT timezone FROM user_profiles WHERE user_id = '${U}'`,
  'today: sessions completed today': `SELECT id, completed_at, active_seconds FROM workout_sessions WHERE user_id = '${U}' AND status = 'completed' AND completed_at >= '2026-10-28 18:30Z' AND completed_at < '2026-10-29 18:30Z'`,
  'today: streak (completed since 60 days)': `SELECT completed_at FROM workout_sessions WHERE user_id = '${U}' AND status = 'completed' AND completed_at >= '2026-08-30 18:30Z'`,
  'today: session XP awards': `SELECT id, amount, source_id FROM xp_transactions WHERE user_id = '${U}' AND source_type = 'workout_session' AND source_id IN (SELECT id FROM workout_sessions WHERE user_id = '${U}' AND completed_at >= '2026-10-01')`,
  'today: food totals': `SELECT date, sum(energy_kcal) FROM food_logs WHERE user_id = '${U}' AND date = '2026-10-29' GROUP BY date`,
  'today: water total': `SELECT date, sum(amount_ml) FROM water_logs WHERE user_id = '${U}' AND date = '2026-10-29' GROUP BY date`,
  // Session list and detail.
  'sessions: history page': `SELECT * FROM workout_sessions WHERE user_id = '${U}' AND status = 'completed' AND completed_at IS NOT NULL ORDER BY completed_at DESC, id DESC LIMIT 21`,
  'sessions: sets for a history page': `SELECT * FROM exercise_set_logs WHERE session_id IN ${LAST21} ORDER BY set_index, logged_at, id`,
  'sessions: detail row': `SELECT * FROM workout_sessions WHERE id = ${LATEST} AND user_id = '${U}'`,
  'sessions: detail sets': `SELECT * FROM exercise_set_logs WHERE session_id = ${LATEST} ORDER BY set_index, logged_at, id`,
  'sessions: completed-set count on complete': `SELECT count(*) FROM exercise_set_logs WHERE session_id = ${LATEST} AND completed`,
  // XP ledger page.
  'xp: ledger first page': `SELECT * FROM xp_transactions WHERE user_id = '${U}' ORDER BY created_at DESC, id DESC LIMIT 21`,
  'xp: ledger next page': `SELECT * FROM xp_transactions WHERE user_id = '${U}' AND (created_at, id) < ('2026-06-01', 'ffffffff-ffff-4fff-bfff-ffffffffffff') ORDER BY created_at DESC, id DESC LIMIT 21`,
  'xp: balance': `SELECT sum(amount) FROM xp_transactions WHERE user_id = '${U}'`,
  // Nutrition day.
  'nutrition: food logs for a day': `SELECT * FROM food_logs WHERE user_id = '${U}' AND date = '2026-06-01' ORDER BY created_at, id`,
  'nutrition: water for a day': `SELECT sum(amount_ml) FROM water_logs WHERE user_id = '${U}' AND date = '2026-06-01'`,
  'nutrition: targets in effect': `SELECT * FROM profile_target_history WHERE user_id = '${U}' AND effective_from <= '2026-06-01' ORDER BY effective_from`,
  // Progression suggestions (GET /workout-plans/{id}/progression).
  'progression: plan': `SELECT * FROM workout_plans WHERE id = '${PLAN}' AND user_id = '${U}'`,
  'progression: versions': `SELECT * FROM workout_plan_versions WHERE plan_id = '${PLAN}' ORDER BY version`,
  'progression: planned workouts': `SELECT * FROM planned_workouts WHERE plan_version_id IN ${VERSIONS} ORDER BY "order"`,
  'progression: planned exercises': `SELECT * FROM planned_exercises WHERE planned_workout_id IN (SELECT id FROM planned_workouts WHERE plan_version_id IN ${VERSIONS}) ORDER BY "order"`,
  'progression: history for one exercise': `SELECT s.id, s.completed_at FROM workout_sessions s
     WHERE s.user_id = '${U}' AND s.status = 'completed' AND s.completed_at IS NOT NULL
       AND EXISTS (SELECT 1 FROM workout_plan_versions v WHERE v.id = s.plan_version_id AND v.plan_id = '${PLAN}')
       AND EXISTS (SELECT 1 FROM exercise_set_logs l WHERE l.session_id = s.id AND l.exercise_id = '${EX(3)}' AND l.completed)
     ORDER BY s.completed_at DESC, s.id LIMIT 8`,
  // Challenges and badges.
  'badges: list': `SELECT * FROM user_badges WHERE user_id = '${U}'`,
  'badges: completed-set count': `SELECT count(*) FROM exercise_set_logs l JOIN workout_sessions s ON s.id = l.session_id WHERE l.user_id = '${U}' AND l.completed AND s.status = 'completed'`,
  'challenges: list page': `SELECT * FROM user_challenges WHERE user_id = '${U}' ORDER BY joined_at DESC, id DESC LIMIT 21`,
  'challenges: active': `SELECT * FROM user_challenges WHERE user_id = '${U}' AND status = 'active' ORDER BY joined_at, id`,
  'challenges: weekly completed count': `SELECT count(*) FROM user_challenges WHERE user_id = '${U}' AND status = 'completed' AND completed_at >= '2026-10-25 18:30Z' AND completed_at < '2026-11-01 18:30Z'`,
  'challenges: already joined this week': `SELECT count(*) FROM user_challenges WHERE user_id = '${U}' AND template_code = 'weekly_0' AND window_start = '2026-10-26'`,
  // FK checks fired by DELETE /me (ON DELETE SET NULL / CASCADE), as the RI triggers run them per deleted parent row.
  'fk: sessions by plan version (SET NULL)': `SELECT 1 FROM ONLY workout_sessions x WHERE plan_version_id = '${OTHER_VERSION}' FOR KEY SHARE OF x`,
  'fk: sessions by planned workout (SET NULL)': `SELECT 1 FROM ONLY workout_sessions x WHERE planned_workout_id = '${OTHER_WORKOUT}' FOR KEY SHARE OF x`,
  'fk: idempotency keys by user (CASCADE)': `SELECT 1 FROM ONLY idempotency_keys x WHERE user_id = '${OTHER}' FOR KEY SHARE OF x`,
};

/** DELETE /me for another user: the FK cascades / SET NULLs it fires. Run inside a rolled-back transaction. */
export const DELETE_ME = `DELETE FROM users WHERE id = '${OTHER}'`;

/** Indexes this review adds and drops (migration 20261029001600_index_review). */
export const ADDED = [
  'workout_sessions_plan_version_id_idx',
  'workout_sessions_planned_workout_id_idx',
  'idempotency_keys_user_id_idx',
];
export const DROPPED = ['workout_plans_user_id_idx', 'workout_sessions_user_id_completed_at_idx'];

/** Back to the pre-review index set (for the before plans, inside a rolled-back transaction). */
export const TO_BEFORE = [
  ...ADDED.map((i) => `DROP INDEX ${i}`),
  `CREATE INDEX workout_plans_user_id_idx ON workout_plans (user_id)`,
  `CREATE INDEX workout_sessions_user_id_completed_at_idx ON workout_sessions (user_id, completed_at)`,
];
