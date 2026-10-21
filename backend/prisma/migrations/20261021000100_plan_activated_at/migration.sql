-- When a workout plan last became the user's active plan; null = never activated.
ALTER TABLE "workout_plans" ADD COLUMN "activated_at" TIMESTAMPTZ(3);

-- Backfill: the true activation time of currently active plans was never stored.
-- created_at is a safe lower bound (a plan can't be activated before it exists),
-- unlike updated_at, which also moves on later edits. Plans that were active
-- earlier but aren't now stay null: there's no record of that.
UPDATE "workout_plans" p
SET "activated_at" = p."created_at"
FROM "user_profiles" u
WHERE u."active_plan_id" = p."id";
