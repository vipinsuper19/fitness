-- Index review (docs/perf/index-review.md): one pass over the hot paths on seeded data.

-- Redundant: (user_id) is a prefix of workout_plans_user_id_archived_at_idx.
DROP INDEX "workout_plans_user_id_idx";

-- Redundant: every (user_id, completed_at) query also filters status = 'completed' and is served
-- by workout_sessions_user_id_status_completed_at_idx.
DROP INDEX "workout_sessions_user_id_completed_at_idx";

-- Unindexed FK columns: deleting a plan version / planned workout (DELETE /me cascades through
-- the user's plans) runs ON DELETE SET NULL on workout_sessions, a seq scan per deleted row.
CREATE INDEX "workout_sessions_plan_version_id_idx" ON "workout_sessions"("plan_version_id");
CREATE INDEX "workout_sessions_planned_workout_id_idx" ON "workout_sessions"("planned_workout_id");

-- Unindexed FK column: DELETE /me cascades into idempotency_keys by user_id (seq scan).
CREATE INDEX "idempotency_keys_user_id_idx" ON "idempotency_keys"("user_id");

