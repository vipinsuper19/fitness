-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateEnum
CREATE TYPE "Sex" AS ENUM ('male', 'female', 'other', 'prefer_not_to_say');

-- CreateEnum
CREATE TYPE "ActivityLevel" AS ENUM ('sedentary', 'light', 'moderate', 'active', 'very_active');

-- CreateEnum
CREATE TYPE "ExperienceLevel" AS ENUM ('beginner', 'intermediate', 'advanced');

-- CreateEnum
CREATE TYPE "UnitSystem" AS ENUM ('metric', 'imperial');

-- CreateEnum
CREATE TYPE "DietPreference" AS ENUM ('vegetarian', 'eggetarian', 'non_vegetarian', 'vegan', 'jain');

-- CreateEnum
CREATE TYPE "GoalType" AS ENUM ('lose_weight', 'gain_muscle', 'maintain', 'improve_endurance', 'workouts_per_week');

-- CreateEnum
CREATE TYPE "GoalStatus" AS ENUM ('active', 'achieved', 'abandoned');

-- CreateEnum
CREATE TYPE "MuscleGroup" AS ENUM ('chest', 'back', 'shoulders', 'biceps', 'triceps', 'forearms', 'core', 'quads', 'hamstrings', 'glutes', 'calves', 'full_body', 'cardio');

-- CreateEnum
CREATE TYPE "Equipment" AS ENUM ('none', 'dumbbell', 'barbell', 'kettlebell', 'machine', 'cable', 'resistance_band', 'bench', 'pull_up_bar', 'other');

-- CreateEnum
CREATE TYPE "TrackingType" AS ENUM ('reps_weight', 'reps', 'duration', 'distance_duration');

-- CreateEnum
CREATE TYPE "PlanSource" AS ENUM ('rule_based', 'custom');

-- CreateEnum
CREATE TYPE "SessionStatus" AS ENUM ('in_progress', 'paused', 'completed');

-- CreateEnum
CREATE TYPE "ServingUnit" AS ENUM ('g', 'ml', 'piece', 'katori', 'cup', 'tbsp', 'tsp', 'plate', 'glass', 'slice');

-- CreateEnum
CREATE TYPE "FoodSource" AS ENUM ('catalog', 'custom');

-- CreateEnum
CREATE TYPE "MealType" AS ENUM ('breakfast', 'lunch', 'dinner', 'snack');

-- CreateEnum
CREATE TYPE "XpSourceType" AS ENUM ('workout_session', 'adjustment');

-- CreateEnum
CREATE TYPE "IdempotencyStatus" AS ENUM ('in_progress', 'completed');

-- CreateTable
CREATE TABLE "users" (
    "id" UUID NOT NULL,
    "email" VARCHAR(254) NOT NULL,
    "password_hash" TEXT NOT NULL,
    "display_name" VARCHAR(60),
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "refresh_tokens" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "family_id" UUID NOT NULL,
    "token_hash" CHAR(64) NOT NULL,
    "expires_at" TIMESTAMPTZ(3) NOT NULL,
    "revoked_at" TIMESTAMPTZ(3),
    "replaced_by_id" UUID,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "refresh_tokens_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "user_profiles" (
    "user_id" UUID NOT NULL,
    "birth_year" SMALLINT,
    "sex" "Sex",
    "height_cm" DECIMAL(5,1),
    "weight_kg" DECIMAL(5,2),
    "activity_level" "ActivityLevel",
    "experience_level" "ExperienceLevel",
    "diet_preference" "DietPreference",
    "unit_system" "UnitSystem" NOT NULL DEFAULT 'metric',
    "timezone" VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    "locale" VARCHAR(16) NOT NULL DEFAULT 'en-IN',
    "currency" CHAR(3) NOT NULL DEFAULT 'INR',
    "daily_calorie_target_kcal" INTEGER,
    "daily_water_target_ml" INTEGER,
    "onboarding_completed" BOOLEAN NOT NULL DEFAULT false,
    "active_plan_id" UUID,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "user_profiles_pkey" PRIMARY KEY ("user_id")
);

-- CreateTable
CREATE TABLE "fitness_goals" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "type" "GoalType" NOT NULL,
    "status" "GoalStatus" NOT NULL DEFAULT 'active',
    "target_value" DECIMAL(8,2),
    "start_value" DECIMAL(8,2),
    "target_date" DATE,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "fitness_goals_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "exercises" (
    "id" UUID NOT NULL,
    "slug" VARCHAR(80) NOT NULL,
    "name" VARCHAR(120) NOT NULL,
    "name_hi" VARCHAR(120),
    "primary_muscle" "MuscleGroup" NOT NULL,
    "secondary_muscles" "MuscleGroup"[],
    "equipment" "Equipment"[],
    "tracking_type" "TrackingType" NOT NULL,
    "difficulty" "ExperienceLevel" NOT NULL,
    "instructions" TEXT[],
    "video_url" TEXT,

    CONSTRAINT "exercises_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "workout_plans" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "source" "PlanSource" NOT NULL,
    "current_version" INTEGER NOT NULL DEFAULT 1,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "workout_plans_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "workout_plan_versions" (
    "id" UUID NOT NULL,
    "plan_id" UUID NOT NULL,
    "version" INTEGER NOT NULL,
    "name" VARCHAR(80) NOT NULL,
    "weeks" SMALLINT NOT NULL,
    "rationale" VARCHAR(1000),
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "workout_plan_versions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "planned_workouts" (
    "id" UUID NOT NULL,
    "plan_version_id" UUID NOT NULL,
    "day_of_week" SMALLINT NOT NULL,
    "name" VARCHAR(80) NOT NULL,
    "order" SMALLINT NOT NULL DEFAULT 0,

    CONSTRAINT "planned_workouts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "planned_exercises" (
    "id" UUID NOT NULL,
    "planned_workout_id" UUID NOT NULL,
    "exercise_id" UUID NOT NULL,
    "order" SMALLINT NOT NULL,
    "sets" SMALLINT NOT NULL,
    "reps_min" SMALLINT,
    "reps_max" SMALLINT,
    "duration_seconds" INTEGER,
    "target_weight_kg" DECIMAL(6,2),
    "rest_seconds" SMALLINT NOT NULL DEFAULT 90,
    "notes" VARCHAR(500),

    CONSTRAINT "planned_exercises_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "workout_sessions" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "plan_version_id" UUID,
    "planned_workout_id" UUID,
    "status" "SessionStatus" NOT NULL DEFAULT 'in_progress',
    "started_at" TIMESTAMPTZ(3) NOT NULL,
    "paused_at" TIMESTAMPTZ(3),
    "completed_at" TIMESTAMPTZ(3),
    "active_seconds" INTEGER NOT NULL DEFAULT 0,
    "perceived_difficulty" SMALLINT,
    "notes" VARCHAR(1000),
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "workout_sessions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "exercise_set_logs" (
    "id" UUID NOT NULL,
    "session_id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "client_set_id" UUID NOT NULL,
    "exercise_id" UUID NOT NULL,
    "set_index" SMALLINT NOT NULL,
    "reps" SMALLINT,
    "weight_kg" DECIMAL(6,2),
    "duration_seconds" INTEGER,
    "distance_m" DECIMAL(9,1),
    "completed" BOOLEAN NOT NULL DEFAULT false,
    "logged_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "exercise_set_logs_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "food_items" (
    "id" UUID NOT NULL,
    "owner_id" UUID,
    "slug" VARCHAR(80),
    "name" VARCHAR(120) NOT NULL,
    "name_hi" VARCHAR(120),
    "brand" VARCHAR(80),
    "serving_size" DECIMAL(8,2) NOT NULL,
    "serving_unit" "ServingUnit" NOT NULL,
    "grams_per_serving" DECIMAL(8,2),
    "energy_kcal" DECIMAL(7,1) NOT NULL,
    "protein_g" DECIMAL(6,1) NOT NULL,
    "carbs_g" DECIMAL(6,1) NOT NULL,
    "fat_g" DECIMAL(6,1) NOT NULL,
    "fiber_g" DECIMAL(6,1),
    "sugar_g" DECIMAL(6,1),
    "sodium_mg" DECIMAL(7,1),
    "is_vegetarian" BOOLEAN,
    "source" "FoodSource" NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "food_items_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "food_logs" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "food_id" UUID NOT NULL,
    "date" DATE NOT NULL,
    "meal_type" "MealType" NOT NULL,
    "servings" DECIMAL(6,2) NOT NULL,
    "energy_kcal" DECIMAL(7,1) NOT NULL,
    "protein_g" DECIMAL(6,1) NOT NULL,
    "carbs_g" DECIMAL(6,1) NOT NULL,
    "fat_g" DECIMAL(6,1) NOT NULL,
    "fiber_g" DECIMAL(6,1),
    "sugar_g" DECIMAL(6,1),
    "sodium_mg" DECIMAL(7,1),
    "nutrients_edited" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "food_logs_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "water_logs" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "date" DATE NOT NULL,
    "amount_ml" INTEGER NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "water_logs_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "body_measurements" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "date" DATE NOT NULL,
    "weight_kg" DECIMAL(5,2),
    "body_fat_pct" DECIMAL(4,1),
    "waist_cm" DECIMAL(5,1),
    "chest_cm" DECIMAL(5,1),
    "hip_cm" DECIMAL(5,1),
    "arm_cm" DECIMAL(5,1),
    "thigh_cm" DECIMAL(5,1),
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "body_measurements_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "xp_transactions" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "source_type" "XpSourceType" NOT NULL,
    "source_id" UUID NOT NULL,
    "amount" INTEGER NOT NULL,
    "reason" VARCHAR(200) NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "xp_transactions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "idempotency_keys" (
    "id" UUID NOT NULL,
    "scope" VARCHAR(80) NOT NULL,
    "key" UUID NOT NULL,
    "user_id" UUID,
    "method" VARCHAR(8) NOT NULL,
    "path" VARCHAR(300) NOT NULL,
    "request_hash" CHAR(64) NOT NULL,
    "status" "IdempotencyStatus" NOT NULL DEFAULT 'in_progress',
    "response_status" SMALLINT,
    "response_body" JSONB,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expires_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "idempotency_keys_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "users_email_key" ON "users"("email");

-- CreateIndex
CREATE UNIQUE INDEX "refresh_tokens_token_hash_key" ON "refresh_tokens"("token_hash");

-- CreateIndex
CREATE INDEX "refresh_tokens_user_id_idx" ON "refresh_tokens"("user_id");

-- CreateIndex
CREATE INDEX "refresh_tokens_family_id_idx" ON "refresh_tokens"("family_id");

-- CreateIndex
CREATE UNIQUE INDEX "user_profiles_active_plan_id_key" ON "user_profiles"("active_plan_id");

-- CreateIndex
CREATE INDEX "fitness_goals_user_id_status_idx" ON "fitness_goals"("user_id", "status");

-- CreateIndex
CREATE UNIQUE INDEX "exercises_slug_key" ON "exercises"("slug");

-- CreateIndex
CREATE INDEX "exercises_primary_muscle_idx" ON "exercises"("primary_muscle");

-- CreateIndex
CREATE INDEX "workout_plans_user_id_idx" ON "workout_plans"("user_id");

-- CreateIndex
CREATE UNIQUE INDEX "workout_plan_versions_plan_id_version_key" ON "workout_plan_versions"("plan_id", "version");

-- CreateIndex
CREATE INDEX "planned_workouts_plan_version_id_idx" ON "planned_workouts"("plan_version_id");

-- CreateIndex
CREATE INDEX "planned_exercises_planned_workout_id_idx" ON "planned_exercises"("planned_workout_id");

-- CreateIndex
CREATE INDEX "workout_sessions_user_id_completed_at_idx" ON "workout_sessions"("user_id", "completed_at");

-- CreateIndex
CREATE INDEX "workout_sessions_user_id_status_idx" ON "workout_sessions"("user_id", "status");

-- CreateIndex
CREATE INDEX "exercise_set_logs_session_id_idx" ON "exercise_set_logs"("session_id");

-- CreateIndex
CREATE UNIQUE INDEX "exercise_set_logs_user_id_client_set_id_key" ON "exercise_set_logs"("user_id", "client_set_id");

-- CreateIndex
CREATE UNIQUE INDEX "food_items_slug_key" ON "food_items"("slug");

-- CreateIndex
CREATE INDEX "food_items_owner_id_idx" ON "food_items"("owner_id");

-- CreateIndex
CREATE INDEX "food_items_name_idx" ON "food_items"("name");

-- CreateIndex
CREATE INDEX "food_logs_user_id_date_idx" ON "food_logs"("user_id", "date");

-- CreateIndex
CREATE INDEX "water_logs_user_id_date_idx" ON "water_logs"("user_id", "date");

-- CreateIndex
CREATE INDEX "body_measurements_user_id_date_idx" ON "body_measurements"("user_id", "date");

-- CreateIndex
CREATE INDEX "xp_transactions_user_id_created_at_idx" ON "xp_transactions"("user_id", "created_at");

-- CreateIndex
CREATE UNIQUE INDEX "xp_transactions_user_id_source_type_source_id_key" ON "xp_transactions"("user_id", "source_type", "source_id");

-- CreateIndex
CREATE INDEX "idempotency_keys_expires_at_idx" ON "idempotency_keys"("expires_at");

-- CreateIndex
CREATE UNIQUE INDEX "idempotency_keys_scope_key_key" ON "idempotency_keys"("scope", "key");

-- AddForeignKey
ALTER TABLE "refresh_tokens" ADD CONSTRAINT "refresh_tokens_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "user_profiles" ADD CONSTRAINT "user_profiles_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "user_profiles" ADD CONSTRAINT "user_profiles_active_plan_id_fkey" FOREIGN KEY ("active_plan_id") REFERENCES "workout_plans"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "fitness_goals" ADD CONSTRAINT "fitness_goals_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "workout_plans" ADD CONSTRAINT "workout_plans_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "workout_plan_versions" ADD CONSTRAINT "workout_plan_versions_plan_id_fkey" FOREIGN KEY ("plan_id") REFERENCES "workout_plans"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "planned_workouts" ADD CONSTRAINT "planned_workouts_plan_version_id_fkey" FOREIGN KEY ("plan_version_id") REFERENCES "workout_plan_versions"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "planned_exercises" ADD CONSTRAINT "planned_exercises_planned_workout_id_fkey" FOREIGN KEY ("planned_workout_id") REFERENCES "planned_workouts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "planned_exercises" ADD CONSTRAINT "planned_exercises_exercise_id_fkey" FOREIGN KEY ("exercise_id") REFERENCES "exercises"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "workout_sessions" ADD CONSTRAINT "workout_sessions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "workout_sessions" ADD CONSTRAINT "workout_sessions_plan_version_id_fkey" FOREIGN KEY ("plan_version_id") REFERENCES "workout_plan_versions"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "workout_sessions" ADD CONSTRAINT "workout_sessions_planned_workout_id_fkey" FOREIGN KEY ("planned_workout_id") REFERENCES "planned_workouts"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "exercise_set_logs" ADD CONSTRAINT "exercise_set_logs_session_id_fkey" FOREIGN KEY ("session_id") REFERENCES "workout_sessions"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "exercise_set_logs" ADD CONSTRAINT "exercise_set_logs_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "exercise_set_logs" ADD CONSTRAINT "exercise_set_logs_exercise_id_fkey" FOREIGN KEY ("exercise_id") REFERENCES "exercises"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "food_items" ADD CONSTRAINT "food_items_owner_id_fkey" FOREIGN KEY ("owner_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "food_logs" ADD CONSTRAINT "food_logs_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "food_logs" ADD CONSTRAINT "food_logs_food_id_fkey" FOREIGN KEY ("food_id") REFERENCES "food_items"("id") ON DELETE NO ACTION ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "water_logs" ADD CONSTRAINT "water_logs_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "body_measurements" ADD CONSTRAINT "body_measurements_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "xp_transactions" ADD CONSTRAINT "xp_transactions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "idempotency_keys" ADD CONSTRAINT "idempotency_keys_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

