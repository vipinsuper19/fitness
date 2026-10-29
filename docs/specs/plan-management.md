# Plan management — contract (P1)

Scope (Tech Lead): **list, pause/resume, archive, unarchive, rename**. Rename is a light call that does not create a version (see [Rename](#rename)); `PATCH /workout-plans/{planId}` still creates a version, but ignores `name` and copies the latest name.

## Endpoints

| Call | Result |
|---|---|
| `GET /workout-plans?status=active\|paused\|inactive\|archived&cursor&limit` | Page of `WorkoutPlanSummary {id, name, source, version, status, isActive, currentWeek, totalWeeks, activatedAt, pausedAt, archivedAt, createdAt, completedSessions}`, newest first; enough for the list screen with no per-plan GET. No `status` = everything except archived. |
| `POST /workout-plans/{planId}/deactivate` | 204. Pauses: if active, clears the profile's active plan and sets `paused_at = now`. Idempotent (inactive/paused/archived → 204, no change). |
| `POST /workout-plans/{planId}/activate` body `{resume?: boolean}` | 200 `WorkoutPlan`. Default restarts at week 1 (unchanged). `resume: true` continues a paused plan's week. |
| `POST /workout-plans/{planId}/unarchive` | 200 `WorkoutPlan`. Clears `archivedAt`; back to `paused` or `inactive`, never active. Not archived → 200, no change. |
| `PUT /workout-plans/{planId}/name` body `{name}` | 200 `WorkoutPlan` (latest version). Renames in place; no new version. |
| `DELETE /workout-plans/{planId}` | 204. Archives (soft delete). Deactivates first if active. Repeat = no-op (keeps the first `archivedAt`). |

Unknown or other users' plans → 404 (malformed ids too, as elsewhere). All writes take an optional `Idempotency-Key`.

**Regenerate** = `POST /workout-plans/generate` → `POST /workout-plans` → `POST /workout-plans/{id}/activate`; then optionally archive the old plan.

## Rules

- Archived plans are read-only: `PATCH`, rename and `activate` → `409 PLAN_ARCHIVED`. `GET /workout-plans/{planId}` (any version) still works, with `archivedAt` set.
- Sessions already started from an archived plan can still be logged and completed (offline sync); history and the XP ledger never change.
- `completedSessions` counts completed, non-discarded sessions on any version of the plan.

## Unarchive

- **Endpoint:** `POST /workout-plans/{planId}/unarchive`, with no body. It follows the action style of `/activate` and `/deactivate`, and returns 200 with the latest-version `WorkoutPlan`.
- **Resulting status:** the call sets `archived_at = null` and changes nothing else.
  - A plan paused when it was archived (archive keeps `paused_at`) comes back `paused`, and `activate { resume: true }` continues its week. **Archiving an active plan pauses it first** (`pauseIfActive` sets `paused_at` = archive time), so an active plan that was archived also comes back `paused`.
  - Only a plan that was inactive when archived comes back `inactive`.
  - It never becomes active: the profile's `active_plan_id` is untouched, and the current active plan stays active.
- **Version:** no new version. `version`, all version contents and names, `activatedAt` and `createdAt` are unchanged. `workout_plans.updated_at` is bumped (not exposed).
- **Not archived:** active, paused and inactive plans (including one just unarchived) return `200` with the plan and no change. This is idempotent. **Difference from archive and deactivate:** they return `204`, but unarchive always returns `200` with the plan, so the client gets the restored `status` and `pausedAt` without another GET.
- **Idempotency:** a retry after success returns 200 with the same plan. An optional `Idempotency-Key` behaves as on other writes: the same key replays the original 200, and the same key with a different request is a conflict (409).
- **Errors:** missing or invalid token → `401`. Unknown plan, another user's plan or a malformed id → `404`.
- **Reads:** afterwards the plan appears in `GET /workout-plans` with no `status` filter, and under `?status=paused` or `inactive`. It no longer appears under `?status=archived`. `GET /workout-plans/active` is unchanged. `PATCH`, rename and `activate` work on it again.
- **No migration needed.**

### Planned tests (implementation PR)

- Archive an inactive plan, then unarchive it: 200, `archivedAt` null, `status` `inactive`, `version` unchanged, no new version row, and the list filters move it from `archived` to `inactive`.
- Pause a plan, archive it, then unarchive it: `status` `paused` with the same `pausedAt`. `activate { resume: true }` then continues the week (with a faked clock).
- Archive the active plan, then unarchive it: `paused` (archive calls `pauseIfActive`), `pausedAt` equals the archive time, and `/active` returns 204. If another plan was activated meanwhile, that plan stays active.
- `activatedAt` is unchanged. `PATCH` and `activate` return 409 `PLAN_ARCHIVED` before the call and 200 after.
- Not archived (active, paused or inactive): 200 with the plan, and no change to status, `pausedAt` or `updated_at`.
- A second unarchive without a key returns 200 with an identical body. With the same `Idempotency-Key`, it replays the original 200.
- 401 without a token. 404 for an unknown id, another user's plan and a malformed id.
- Sessions, history and the XP ledger are unchanged.

## Rename

- **Endpoint:** `PUT /workout-plans/{planId}/name`, body `{ "name": string }` (`PlanRename`, no other fields). This follows the existing sub-resource and action style (`/deactivate`, `/activate`). `PUT` is used because setting a name is naturally idempotent.
- **Validation:** the server trims leading and trailing whitespace (as for other free-text fields). After trimming, the name must be 1–80 characters, the same limit as the draft `name` and `workout_plan_versions.name varchar(80)`. A name that is **empty after trimming** (`""`, `"   "`) returns `400 VALIDATION_FAILED`, as do a missing `name`, a non-string `name` and extra fields. Inner whitespace is kept as sent.
- **Storage and versions:** the call updates `workout_plan_versions.name` of the **latest** version only (`version = current_version`). `currentVersion` does not change, no version is created, and earlier versions keep their saved names. It needs no migration. `workout_plans.updated_at` is bumped (it is `@updatedAt`; not exposed in the API). The version's `createdAt` does not change.
- **Status:** active, paused and inactive plans can be renamed, and their status, `activatedAt`, `pausedAt`, `currentWeek` and `planEnded` are unchanged. **Archived plans are read-only, so any rename returns `409 PLAN_ARCHIVED`, even one to the same name.** The archived check comes before the same-name no-op.
- **Idempotency:** renaming to the current name (after trimming) returns 200 with no write and no `updated_at` bump. Repeating the same call gives the same result. An optional `Idempotency-Key` behaves as on other writes: the same key with the same body replays, and the same key with a different body returns `409`.
- **PATCH ignores `name`:** `PATCH /workout-plans/{planId}` (`WorkoutPlanUpdate`) still accepts `name` as an optional field for compatibility, but ignores it and never validates it (empty or longer than 80 characters is fine): the new version always copies the latest version's name. `PUT /workout-plans/{planId}/name` is the only way to rename. `POST /workout-plans` still requires `name`. Clients may stop sending it on PATCH.
- **Concurrency (accepted):** there is no `baseVersion`, so the last write wins. A rename racing a `PATCH` either lands on the old latest version (and the new version copies the pre-rename name) or on the new one. The Tech Lead has accepted this for a display name.
- **Errors:** missing or invalid token → `401`. An unknown plan, another user's plan or a malformed id → `404`.
- **Reads:** the new name shows immediately in `GET /workout-plans` (`WorkoutPlanSummary.name` = latest version's name), `GET /workout-plans/active` and `GET /workout-plans/{planId}` (latest). `?version=N` for an older version shows that version's name. List order is unchanged, because it is by `createdAt`, not `updated_at`. Session history keeps referring to the version id; anything that shows a plan name for a session on the latest version shows the new name.
- **XP and badges:** none awarded.

### Planned tests (implementation PR)

- Rename an active plan: 200, `name` trimmed (`"  Push Pull  "` → `"Push Pull"`), `version` unchanged, no new `workout_plan_versions` row, and `/active` and the list show the new name.
- Rename after a `PATCH` (v2): only v2 changes; `?version=1` keeps the old name.
- PATCH with a different `name` → the new version keeps the current name.
- PATCH with `name: ""` or an overlong `name` → 200 (not validated), and the new version keeps the current name.
- PATCH without `name` → the new version keeps the current name.
- Rename, then replay a stale PATCH draft (with the pre-rename `name`, and the correct `baseVersion`) → the new version has the renamed name.
- Rename paused and inactive plans: 200; status, `pausedAt`, `activatedAt` and `currentWeek` are unchanged (with a faked clock).
- Validation: `""`, `"   "`, 81 characters after trim, a missing `name`, `name: 3` and an extra field each return 400 `VALIDATION_FAILED`. 80 characters with surrounding spaces is accepted.
- Same name (including the same name with surrounding spaces): 200, and `updated_at` is unchanged.
- Archived plan: 409 `PLAN_ARCHIVED` for a new name and for the same name (no write, `updated_at` unchanged).
- Idempotency-Key: a replay returns the same body; the same key with a different name returns 409.
- 401 without a token. 404 for an unknown id, another user's plan and a malformed id.

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
- Archiving a paused plan keeps `paused_at` (history); it is then `archived` and can't be resumed (409 `PLAN_ARCHIVED`) until it is unarchived.
- Activating the already-active plan: no change, with or without `resume`.
- Resuming a plan that had already ended: `currentWeek = weeks`, `planEnded: true`.
- Weeks are local calendar days in the profile timezone counted from the *shifted* `activatedAt`. A short pause across local midnight therefore keeps the day count tied to the wall clock: e.g. activated 09:00 IST, paused 23:50 IST on day 7, resumed 00:10 IST the next day → day 8, week 2 (the 20-minute shift doesn't move the start day). If the shift moves `activatedAt` past midnight, the start day moves with it.
- Two concurrent activations (same or different plans) leave exactly one active plan (the last write wins); neither fails.
- Old servers ignore the activate body: `resume` there restarts at week 1.

## Progress fields with no active plan

- `GET /workout-plans/active` → **204, empty body**. This is today's behaviour on prod (`plans.controller.ts` sets 204 when there is no active plan) and it does not change, so Android needs no dual handling for this call. Before this API ships the user can't pause or archive, so "no active plan" only happens for users who never activated one.
- Before deploy: `GET /workout-plans`, `deactivate` and `DELETE` don't exist (404 `NOT_FOUND` route), and `activate` ignores `{resume}`. Android should hide pause/archive/list on a 404 from `GET /workout-plans`.
- Every plan reports `isActive: false`, `currentWeek: null`, `planEnded: null` (they are computed only for the active plan).
- `activatedAt` keeps its last value while paused. To continue, call `activate` with `{resume: true}`: `activatedAt` shifts forward by the pause and `currentWeek` picks up where it stopped (see *Pause and resume*). `activate` with no body or `resume: false` sets `activatedAt = now`, so `currentWeek` restarts at 1.
- Resuming a plan whose last week was already over before the pause gives `currentWeek = weeks` and `planEnded: true` (the shift preserves the elapsed days; the usual cap applies). Use a default activate to start it over at week 1.

## Database (implementation PR)

- Migration: `ALTER TABLE workout_plans ADD COLUMN archived_at timestamptz(3) NULL, ADD COLUMN paused_at timestamptz(3) NULL` + index `(user_id, archived_at)`. Both null for existing rows (existing plans are `active` or `inactive`).
- Deactivate: if `user_profiles.active_plan_id` equals the plan, set it NULL and `workout_plans.paused_at = now()` (same tx, profile row locked).
- Activate: sets `active_plan_id`; `activated_at` = now, or `activated_at + (now − paused_at)` on resume; `paused_at = NULL`.
- Archive: in one tx, clear `active_plan_id` if it is this plan, then `archived_at = now()` where it is null. No rows are deleted; `workout_sessions`, set logs, `xp_transactions` and plan versions are untouched.
- Export (`GET /me/export`) includes `archivedAt` on plans.
