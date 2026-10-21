-- P1 badges & challenges (docs/specs/badges-challenges.md). Additive only: new enums,
-- enum values, tables and a defaulted column; no existing rows change.
-- ADD VALUE runs in the migration transaction (PG 12+); the new values are not used here.
-- CreateEnum
CREATE TYPE "ChallengeType" AS ENUM ('weekly', 'personal');

-- CreateEnum
CREATE TYPE "ChallengeStatus" AS ENUM ('active', 'completed', 'expired', 'left');

-- AlterEnum


ALTER TYPE "XpSourceType" ADD VALUE 'badge';
ALTER TYPE "XpSourceType" ADD VALUE 'challenge';

-- AlterTable
ALTER TABLE "user_profiles" ADD COLUMN     "badges_evaluated_version" INTEGER NOT NULL DEFAULT 0;

-- CreateTable
CREATE TABLE "user_badges" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "badge_code" VARCHAR(40) NOT NULL,
    "earned_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "catalog_version" INTEGER NOT NULL,

    CONSTRAINT "user_badges_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "user_challenges" (
    "id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "template_code" VARCHAR(40) NOT NULL,
    "type" "ChallengeType" NOT NULL,
    "target" INTEGER NOT NULL,
    "window_start" DATE NOT NULL,
    "window_end" DATE,
    "timezone" VARCHAR(64) NOT NULL,
    "water_goal_ml" INTEGER,
    "status" "ChallengeStatus" NOT NULL DEFAULT 'active',
    "progress" INTEGER NOT NULL DEFAULT 0,
    "xp_awarded" INTEGER NOT NULL DEFAULT 0,
    "joined_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "join_date" DATE NOT NULL,
    "completed_at" TIMESTAMPTZ(3),
    "left_at" TIMESTAMPTZ(3),
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "user_challenges_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "user_badges_user_id_earned_at_idx" ON "user_badges"("user_id", "earned_at");

-- CreateIndex
CREATE UNIQUE INDEX "user_badges_user_id_badge_code_key" ON "user_badges"("user_id", "badge_code");

-- CreateIndex
CREATE INDEX "user_challenges_user_id_joined_at_id_idx" ON "user_challenges"("user_id", "joined_at" DESC, "id" DESC);

-- CreateIndex
CREATE INDEX "user_challenges_user_id_status_joined_at_id_idx" ON "user_challenges"("user_id", "status", "joined_at" DESC, "id" DESC);

-- AddForeignKey
ALTER TABLE "user_badges" ADD CONSTRAINT "user_badges_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "user_challenges" ADD CONSTRAINT "user_challenges_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Not expressible in Prisma: at most one *active* challenge per template.
CREATE UNIQUE INDEX "user_challenges_one_active_per_template" ON "user_challenges"("user_id", "template_code") WHERE "status" = 'active';

-- Not expressible in Prisma: one weekly instance per (user, template, week). Personal
-- challenges are exempt, so leaving and re-joining one on the same day works.
CREATE UNIQUE INDEX "user_challenges_one_weekly_instance" ON "user_challenges"("user_id","template_code","window_start") WHERE "type" = 'weekly';

-- Sanity checks, matching the style of 20261020000100_check_constraints.
ALTER TABLE "user_challenges"
  ADD CONSTRAINT "user_challenges_target_positive" CHECK ("target" > 0),
  ADD CONSTRAINT "user_challenges_progress_range" CHECK ("progress" >= 0 AND "progress" <= "target"),
  ADD CONSTRAINT "user_challenges_window_order" CHECK ("window_end" IS NULL OR "window_end" >= "window_start"),
  ADD CONSTRAINT "user_challenges_xp_awarded_range" CHECK ("xp_awarded" BETWEEN 0 AND 150),
  ADD CONSTRAINT "user_challenges_water_goal_range" CHECK ("water_goal_ml" IS NULL OR "water_goal_ml" BETWEEN 250 AND 10000),
  ADD CONSTRAINT "user_challenges_status_times" CHECK (
    ("status" <> 'completed' OR "completed_at" IS NOT NULL) AND ("status" <> 'left' OR "left_at" IS NOT NULL));
