# Plan management — contract (P1)

Scope (Tech Lead): **list, pause/resume, archive**. Unarchive is out for v1. Rename is a later item (today `PATCH /workout-plans/{planId}` with a new `name` creates a new version, which works but is heavier than a rename).

## Endpoints

| Call | Result |
|---|---|
| `GET /workout-plans?status=active\|paused\|inactive\|archived&cursor&limit` | Page of `WorkoutPlanSummary {id, name, source, version, status, isActive, currentWeek, totalWeeks, activatedAt, pausedAt, archivedAt, createdAt, completedSessions}`, newest first; enough for the list screen with no per-plan GET. No `status` = everything except archived. |
| `POST /workout-plans/{planId}/deactivate` | 204. Pauses: if active, clears the profile's active plan and sets `paused_at = now`. Idempotent (inactive/paused/archived → 204, no change). |
| `POST /workout-plans/{planId}/activate` body `{resume?: boolean}` | 200 `WorkoutPlan`. Default restarts at week 1 (unchanged). `resume: true` continues a paused plan's week. |
| `DELETE /workout-plans/{planId}` | 204. Archives (soft delete). Deactivates first if active. Repeat = no-op (keeps the first `archivedAt`). |

Unknown or other users' plans → 404 (malformed ids too, as elsewhere). All writes take an optional `Idempotency-Key`.

**Regenerate** = `POST /workout-plans/generate` → `POST /workout-plans` → `POST /workout-plans/{id}/activate`; then optionally archive the old plan.

## Rules

- Archived plans are read-only: `PATCH` and `activate` → `409 PLAN_ARCHIVED`. `GET /workout-plans/{planId}` (any version) still works, with `archivedAt` set.
- Sessions already started from an archived plan can still be logged and completed (offline sync); history and the XP ledger never change.
- `completedSessions` counts completed, non-discarded sessions on any version of the plan.

## Pause and resume

`status` is derived: `archived` (archivedAt set) → `active` (profile's active plan) → `paused` (pausedAt set) → `inactive`.

- **Pause** (`deactivate` on the active plan): `paused_at = now`, profile `active_plan_id = NULL`.
- **Resume** (`activate` with `{resume: true}` on a paused plan): `activated_at += now − paused_at`, `paused_at = NULL`, plan becomes active. `currentWeek` continues where it stopped (still counted in local calendar days from the shifted `activatedAt`).
- **Default activate** (no body / `resume: false`): `activated_at = now`, `paused_at = NULL` → week 1 (today's behaviour).

Edge cases:
- `resume: true` on a plan never paused (or with `activatedAt` null): normal activation.
- Pausing an archived, inactive or already-paused plan: 204, no change (an archived plan never gains `paused_at`; an existing `paused_at` is not moved).
- Resume after another plan was active in between (pause A, activate B, resume A): A still shifts by the whole pause (`now − A.paused_at`). B becomes `inactive`, not paused.
- Activating another plan while one is paused leaves the paused plan `paused`.
- Archiving a paused plan keeps `paused_at` (history); it is then `archived` and can't be resumed (409 `PLAN_ARCHIVED`). Unarchive is out for v1.
- Activating the already-active plan: no change, with or without `resume`.
- Old servers ignore the activate body: `resume` there restarts at week 1.

## Progress fields with no active plan

- `GET /workout-plans/active` → **204, empty body**. This is today's behaviour on prod (`plans.controller.ts` sets 204 when there is no active plan) and it does not change, so Android needs no dual handling for this call. Before this API ships the user can't pause or archive, so "no active plan" only happens for users who never activated one.
- Before deploy: `GET /workout-plans`, `deactivate` and `DELETE` don't exist (404 `NOT_FOUND` route), and `activate` ignores `{resume}`. Android should hide pause/archive/list on a 404 from `GET /workout-plans`.
- Every plan reports `isActive: false`, `currentWeek: null`, `planEnded: null` (they are computed only for the active plan).
- `activatedAt` keeps its last value. Resuming with `activate` sets `activatedAt = now`, so `currentWeek` restarts at 1 (existing activation rule).

## Database (implementation PR)

- Migration: `ALTER TABLE workout_plans ADD COLUMN archived_at timestamptz(3) NULL, ADD COLUMN paused_at timestamptz(3) NULL` + index `(user_id, archived_at)`. Both null for existing rows (existing plans are `active` or `inactive`).
- Deactivate: if `user_profiles.active_plan_id` equals the plan, set it NULL and `workout_plans.paused_at = now()` (same tx, profile row locked).
- Activate: sets `active_plan_id`; `activated_at` = now, or `activated_at + (now − paused_at)` on resume; `paused_at = NULL`.
- Archive: in one tx, clear `active_plan_id` if it is this plan, then `archived_at = now()` where it is null. No rows are deleted; `workout_sessions`, set logs, `xp_transactions` and plan versions are untouched.
- Export (`GET /me/export`) includes `archivedAt` on plans.
