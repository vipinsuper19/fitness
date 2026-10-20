-- Range checks Prisma cannot express. They mirror contracts/openapi.yaml so
-- bad rows fail in the database too, not only in request validation.

ALTER TABLE "planned_workouts"
  ADD CONSTRAINT "planned_workouts_day_of_week_check" CHECK ("day_of_week" BETWEEN 1 AND 7);

ALTER TABLE "planned_exercises"
  ADD CONSTRAINT "planned_exercises_sets_check" CHECK ("sets" BETWEEN 1 AND 20),
  ADD CONSTRAINT "planned_exercises_reps_check" CHECK ("reps_min" IS NULL OR "reps_max" IS NULL OR "reps_min" <= "reps_max"),
  ADD CONSTRAINT "planned_exercises_rest_check" CHECK ("rest_seconds" BETWEEN 0 AND 600);

ALTER TABLE "workout_plan_versions"
  ADD CONSTRAINT "workout_plan_versions_version_check" CHECK ("version" >= 1),
  ADD CONSTRAINT "workout_plan_versions_weeks_check" CHECK ("weeks" BETWEEN 1 AND 16);

ALTER TABLE "workout_sessions"
  ADD CONSTRAINT "workout_sessions_active_seconds_check" CHECK ("active_seconds" >= 0),
  ADD CONSTRAINT "workout_sessions_difficulty_check" CHECK ("perceived_difficulty" IS NULL OR "perceived_difficulty" BETWEEN 1 AND 5),
  ADD CONSTRAINT "workout_sessions_completed_check" CHECK (("status" = 'completed') = ("completed_at" IS NOT NULL));

ALTER TABLE "exercise_set_logs"
  ADD CONSTRAINT "exercise_set_logs_nonnegative_check" CHECK (
    ("reps" IS NULL OR "reps" >= 0) AND ("weight_kg" IS NULL OR "weight_kg" >= 0)
    AND ("duration_seconds" IS NULL OR "duration_seconds" >= 0) AND ("distance_m" IS NULL OR "distance_m" >= 0)
  );

ALTER TABLE "food_items"
  ADD CONSTRAINT "food_items_serving_check" CHECK ("serving_size" > 0 AND ("grams_per_serving" IS NULL OR "grams_per_serving" > 0)),
  ADD CONSTRAINT "food_items_nutrients_check" CHECK ("energy_kcal" >= 0 AND "protein_g" >= 0 AND "carbs_g" >= 0 AND "fat_g" >= 0),
  ADD CONSTRAINT "food_items_owner_check" CHECK (("source" = 'custom') = ("owner_id" IS NOT NULL));

ALTER TABLE "food_logs"
  ADD CONSTRAINT "food_logs_servings_check" CHECK ("servings" > 0),
  ADD CONSTRAINT "food_logs_nutrients_check" CHECK ("energy_kcal" >= 0 AND "protein_g" >= 0 AND "carbs_g" >= 0 AND "fat_g" >= 0);

ALTER TABLE "water_logs"
  ADD CONSTRAINT "water_logs_amount_check" CHECK ("amount_ml" BETWEEN 1 AND 5000);

ALTER TABLE "user_profiles"
  ADD CONSTRAINT "user_profiles_currency_check" CHECK ("currency" = 'INR');

-- Workout awards are positive; only server adjustments (reversals) are negative.
ALTER TABLE "xp_transactions"
  ADD CONSTRAINT "xp_transactions_amount_check" CHECK ("amount" <> 0 AND ("source_type" = 'adjustment' OR "amount" > 0));

ALTER TABLE "idempotency_keys"
  ADD CONSTRAINT "idempotency_keys_completed_check" CHECK (("status" = 'completed') = ("response_status" IS NOT NULL));
