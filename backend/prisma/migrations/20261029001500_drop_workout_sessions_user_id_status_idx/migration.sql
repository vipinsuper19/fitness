-- workout_sessions_user_id_status_idx (user_id, status) is a strict prefix of
-- workout_sessions_user_id_status_completed_at_idx (user_id, status, completed_at), which serves every
-- (user_id, status[, completed_at]) lookup; the duplicate only cost writes and space (docs/specs/index-audit.md).
DROP INDEX IF EXISTS "workout_sessions_user_id_status_idx";
