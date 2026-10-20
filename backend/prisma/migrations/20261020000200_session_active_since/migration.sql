-- T12: track the running segment so paused time is excluded from active_seconds.
ALTER TABLE "workout_sessions" ADD COLUMN "active_since" TIMESTAMPTZ(3);

-- Existing in-progress rows (none in production yet) resume from their start.
UPDATE "workout_sessions" SET "active_since" = "started_at" WHERE "status" = 'in_progress';

-- Only a running session has a running segment.
ALTER TABLE "workout_sessions"
  ADD CONSTRAINT "workout_sessions_active_since_check" CHECK (("active_since" IS NOT NULL) = ("status" = 'in_progress'));
