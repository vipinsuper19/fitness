-- Strength progress (docs/specs/strength-progress.md) filters completed sessions by user and completed_at.
CREATE INDEX IF NOT EXISTS "workout_sessions_user_id_status_completed_at_idx" ON "workout_sessions"("user_id", "status", "completed_at");
