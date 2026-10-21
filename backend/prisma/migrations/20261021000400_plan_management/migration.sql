-- Plan management (docs/specs/plan-management.md): pause/resume and archive.
-- Additive and nullable; existing plans keep null (status active or inactive).
ALTER TABLE "workout_plans" ADD COLUMN "paused_at" TIMESTAMPTZ(3);
ALTER TABLE "workout_plans" ADD COLUMN "archived_at" TIMESTAMPTZ(3);
CREATE INDEX "workout_plans_user_id_archived_at_idx" ON "workout_plans"("user_id", "archived_at");
