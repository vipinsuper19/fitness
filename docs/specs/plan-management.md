# Plan management — contract (P1)

Scope (Tech Lead): **list, pause, archive**. Rename is a later item (today `PATCH /workout-plans/{planId}` with a new `name` creates a new version, which works but is heavier than a rename).

## Endpoints

| Call | Result |
|---|---|
| `GET /workout-plans?status=active\|inactive\|archived&cursor&limit` | Page of `WorkoutPlanSummary {id, name, source, version, isActive, activatedAt, createdAt, archivedAt, completedSessions}`, newest first. No `status` = active + inactive. |
| `POST /workout-plans/{planId}/deactivate` | 204. Pauses: clears the profile's active plan if it is this one. Idempotent (inactive/archived → 204, no change). |
| `DELETE /workout-plans/{planId}` | 204. Archives (soft delete). Deactivates first if active. Repeat = no-op (keeps the first `archivedAt`). |

Unknown or other users' plans → 404 (malformed ids too, as elsewhere). All writes take an optional `Idempotency-Key`.

**Regenerate** = `POST /workout-plans/generate` → `POST /workout-plans` → `POST /workout-plans/{id}/activate`; then optionally archive the old plan.

## Rules

- Archived plans are read-only: `PATCH` and `activate` → `409 PLAN_ARCHIVED`. `GET /workout-plans/{planId}` (any version) still works, with `archivedAt` set.
- Sessions already started from an archived plan can still be logged and completed (offline sync); history and the XP ledger never change.
- `completedSessions` counts completed, non-discarded sessions on any version of the plan.

## Progress fields with no active plan

- `GET /workout-plans/active` → 204.
- Every plan reports `isActive: false`, `currentWeek: null`, `planEnded: null` (they are computed only for the active plan).
- `activatedAt` keeps its last value. Resuming with `activate` sets `activatedAt = now`, so `currentWeek` restarts at 1 (existing activation rule).

## Database (implementation PR)

- Migration: `ALTER TABLE workout_plans ADD COLUMN archived_at timestamptz(3) NULL` + index `(user_id, archived_at)`.
- Deactivate: `user_profiles.active_plan_id = NULL` where it equals the plan (same tx, row-locked).
- Archive: in one tx, clear `active_plan_id` if it is this plan, then `archived_at = now()` where it is null. No rows are deleted; `workout_sessions`, set logs, `xp_transactions` and plan versions are untouched.
- Export (`GET /me/export`) includes `archivedAt` on plans.
