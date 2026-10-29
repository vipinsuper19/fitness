# Adaptive progression suggestions (contract)

`GET /workout-plans/{planId}/progression-suggestions`: read-only, rule-based suggestions to progress planned exercises, from the user's completed session history. Based on PRD §6 ("If a user consistently completes prescribed sets with manageable effort, the system may suggest a small progression. If they report pain, unusual fatigue, or excessive difficulty, it should not automatically increase load") and the safety rules in PRD §10 (sensible progressions, allow users to modify, no medical claims).

**No AI. Never automatic.** The endpoint only suggests. It never writes to the plan, the sessions or XP. The user applies a suggestion through the existing plan edit (`PATCH /workout-plans/{planId}`).

## Scope
- The plan's **latest version**; one suggestion per planned exercise (`PlannedWorkout.exercises[]` entry, identified by `plannedWorkoutId` + `exerciseId`).
- An exercise listed in **several workouts** gets **one suggestion per entry** (each with its own `plannedWorkoutId`, `order` and `current`); all entries share the same history for that exercise.
- History = the user's **completed** sessions that followed this plan (any version) and logged that exercise; at most the last **N = 3** such sessions, newest first. Only `completed: true` sets count; corrections are applied, because they rewrite the set rows (same counting as `/progress/exercises`).
- Archived plans: `409 PLAN_ARCHIVED` (read-only plans aren't edited). Paused or inactive plans are allowed.

## Trigger (all must hold)
1. **Enough history:** the last N = 3 qualifying sessions exist.
2. **Hit the prescription in each of them**, against the plan version that session followed:
   - at least `sets` completed sets, and
   - `reps_weight` / `reps`: every counted set reached `repsMax` (or `repsMin` when `repsMax` is null), and for `reps_weight` used `weightKg >= targetWeightKg` (when a target is set);
   - `duration`: every counted set reached the planned `durationSeconds`;
   - `distance_duration`: every counted set reached the planned `durationSeconds` (distance is recorded but has no plan target in v1).
3. **Manageable effort:** none of those sessions has `perceivedDifficulty >= 4`.
4. **Cooldown:** no progression of this exercise was applied in the last **7 days** (see below).

Otherwise the suggestion is `hold` or `deload` with a reason.

## Decision order
Per planned exercise: (1) deload check (needs only the latest **2** sessions, so it can fire **before** the 3-session minimum); (2) fewer than 3 sessions → `NOT_ENOUGH_HISTORY`; (3) any miss → `MISSED_TARGET`; (4) any `perceivedDifficulty` ≥ 4 → `HIGH_EFFORT`; (5) cooldown → `COOLDOWN`; (6) the increment rules.

## Increment rules (one step, never more)
| trackingType | Suggestion | Step |
|---|---|---|
| `reps_weight` with `targetWeightKg` | raise `targetWeightKg` | lower-body primary muscle (quads, hamstrings, glutes, calves): **+5%**, otherwise **+2.5%**; rounded **down** to 0.5 kg, minimum +1.0 kg (+0.5 kg when target < 10 kg) |
| `reps_weight` without a target | set `targetWeightKg` | to the heaviest weight used in all N sessions' counted sets, rounded down to 0.5 kg (no increase) |
| `reps` (bodyweight) | raise `repsMin`/`repsMax` | +1 rep each (+2 when `repsMax >= 15`); when `repsMax` would exceed 20, `hold` / `REP_CEILING` with `harderVariations` (below) |
| `duration` | raise `durationSeconds` | +10% rounded **down** to 5 s, minimum +5 s |
| `distance_duration` | raise `durationSeconds` | same as `duration` |

## Cap
- One step per suggestion. The **+10% cap applies to load only** (`targetWeightKg` and `durationSeconds`).
- **Rep steps are exempt from the cap:** +1 rep, or +2 when `repsMax >= 15`, is suggested as is (e.g. 3–5 → 4–6).
- **The minimum step overrides the cap.** When the minimum load step (+1.0 kg, +0.5 kg under 10 kg, +5 s) is more than 10% of a small current value, the minimum step is suggested anyway.
- Values stay within the plan schema limits (`targetWeightKg` ≤ 1000, reps ≤ 100, `durationSeconds` ≤ 7200). An exercise already at a limit is `hold` / `CAP_REACHED`.
- **Every suggested weight is a multiple of 0.5 kg** (increase, deload and `NO_TARGET_SET`; rounded down).

## Cooldown
The server derives "last progression applied" from plan versions: the newest plan version where this exercise's prescribed load (target weight, reps or duration) increased versus the previous version. Only a raise from one set value to a higher one counts: **setting a first target** (null → value, e.g. accepting `NO_TARGET_SET`) does **not** start a cooldown. If its `createdAt` is less than 7 days ago, `hold` with `COOLDOWN` and `cooldownUntil`. No new table is needed.

## Deload
Suggest a **deload** (−10% of `targetWeightKg` rounded down to 0.5 kg, or −10% of `durationSeconds` rounded **down** to 5 s; reps unchanged for `reps`) when either:
- **two consecutive** qualifying sessions missed the prescription (rule 2) for this exercise, or
- the latest two sessions both report `perceivedDifficulty = 5`.
Deloads are checked first and need only the latest 2 sessions, so one can fire before the 3-session minimum. When there is **no load to lower** (a `reps` exercise, or `reps_weight` without `targetWeightKg`), the item is `hold` with the deload reason (`REPEATED_MISSES` or `VERY_HARD`) and `suggested: null`.
A deload is a suggestion like any other; it is never applied automatically. Pain or medical symptoms are **not** inferred from data; the client shows its standard "stop and seek professional advice" copy whenever the user reports pain (out of scope here).

## Response
`ProgressionSuggestions`: `{ planId, planVersion, generatedAt, windowSessions: 3, suggestions: [...] }`. `planVersion` (top level) is the latest version all suggestions were computed against. One item per planned exercise, in plan order:
- `plannedWorkoutId`, `exerciseId`, `order`, `trackingType`
- `action`: `increase | hold | deload`
- `reason`: enum code (stable, for client logic and localisation), one of:
  | Code | Action | Meaning |
  |---|---|---|
  | `ALL_SETS_COMPLETED` | increase | Last 3 sessions met the prescription at manageable effort |
  | `NO_TARGET_SET` | increase | No `targetWeightKg` yet; suggests the heaviest weight used |
  | `NOT_ENOUGH_HISTORY` | hold | Fewer than 3 qualifying sessions; **also** used when there is no value to progress (`reps_weight` with no target and no logged weight, `reps` with no reps range, `duration`/`distance_duration` with no `durationSeconds`) |
  | `MISSED_TARGET` | hold | A session in the window missed sets, reps, weight or duration |
  | `HIGH_EFFORT` | hold | A session in the window had `perceivedDifficulty` ≥ 4 |
  | `COOLDOWN` | hold | Load was raised less than 7 days ago (`cooldownUntil` set) |
  | `CAP_REACHED` | hold | Already at the schema limit |
  | `REP_CEILING` | hold | Bodyweight reps at the 20-rep ceiling; see `harderVariations` |
  | `REPEATED_MISSES` | deload (hold when no load to lower) | Two consecutive sessions missed the prescription |
  | `VERY_HARD` | deload (hold when no load to lower) | Two latest sessions at `perceivedDifficulty` 5 |
- `reasonText`: English sentence for display, **always present** (non-null) in v1; clients may localise from `reason` instead.
- `current`: `PrescriptionValues` `{ sets, repsMin, repsMax, targetWeightKg, durationSeconds }`, the latest plan version's exact values.
- `suggested`: for `increase`/`deload`, the **complete** `PrescriptionValues` to write into that `PlannedExercise` (unchanged fields copied from `current`), so the client builds the PATCH without computing anything; `null` for `hold`. v1 never changes `sets`.
- `harderVariations`: optional array of `exerciseId`s, present only with `REP_CEILING` and possibly empty. Derived **by rule** from the catalogue; v1 has **no curated progression links**: exercises with the same `primaryMuscle` and `trackingType`, `difficulty` exactly one level above this exercise's (`beginner → intermediate → advanced`), and equipment the original also uses (or none); at most 3. Ordering is **deterministic**: by exercise `name` ascending, then `id` ascending; the first 3 are returned. Empty when none match or the exercise is already `advanced`.
- `cooldownUntil`: date-time or null
- `basedOn`: up to 3 items `{ sessionId, completedAt, perceivedDifficulty|null, completedSets, plannedSets, metTarget, bestReps|null, bestWeightKg|null, bestDurationSeconds|null, bestDistanceM|null }`, newest first.

## Accepting suggestions
`PATCH /workout-plans/{planId}` takes a **full** `WorkoutPlanDraft` (every workout and exercise) plus `baseVersion`, and creates one new version. So **one PATCH can carry every accepted suggestion**: the client takes the plan at `planVersion`, replaces `sets/repsMin/repsMax/targetWeightKg/durationSeconds` of each accepted exercise (matched by `plannedWorkoutId` + `exerciseId`) with `suggested`, and sends it with `baseVersion = planVersion`. A stale version gets the existing `409 VERSION_CONFLICT`; the client refetches the plan and suggestions. After a successful PATCH, suggestions computed for the old version are stale (accepted ones now enter cooldown); refetch before applying more. Dismissing needs no call (v1 keeps no dismiss state).

## Errors and edge cases
- Auth required (`401`); another user's plan or unknown id → `404`.
- No history (or fewer than 3 qualifying sessions): `200` with every item `hold` / `NOT_ENOUGH_HISTORY`, `suggested: null`, `basedOn` listing what exists (possibly `[]`).
- Exercises added in the latest version use only sessions that logged them.
- Read-only: no Idempotency-Key, no XP, no badge evaluation.

## Planned tests
- Auth guard; other user's plan → 404; archived plan → 409 `PLAN_ARCHIVED`.
- Empty history → all `hold` / `NOT_ENOUGH_HISTORY`, `basedOn: []`; 2 sessions → still `NOT_ENOUGH_HISTORY`.
- `reps_weight` upper body 40 kg, 3 met sessions → 41 kg (2.5% = 1.0); lower body 100 kg → 105 kg; 8 kg target → 8.5 kg; rounding down to 0.5 kg.
- Missing target → `NO_TARGET_SET` with the heaviest weight used.
- `reps` 8–12 → 9–13; 15–18 → 17–20; 19–20 → `REP_CEILING` with `harderVariations` (one-level-harder same-muscle exercise listed; `advanced` exercise → `[]`).
- Minimum step overrides the cap: 5 kg → 5.5 kg (+10%), 30 s → 35 s; rep steps are exempt from the cap (3–5 reps → 4–6, 15–18 → 17–20). All suggested weights are multiples of 0.5 kg.
- `suggested` is complete (unchanged fields equal `current`); every item has a `reason` from the enum; `reasonText` optional.
- Accepting two suggestions in one PATCH with `baseVersion = planVersion` creates one version with both changes.
- `duration` 60 s → 65 s; 300 s → 330 s.
- One session missing a set or a rep → `MISSED_TARGET`; `perceivedDifficulty` 4 in the window → `HIGH_EFFORT`.
- Two consecutive misses → deload −10% (weight rounded down to 0.5 kg; duration 65 s → 55 s, rounded down to 5 s); two sessions at difficulty 5 → `VERY_HARD` deload.
- Cooldown: a version raising the load 3 days ago → `COOLDOWN` with `cooldownUntil`; 8 days ago → increase (faked clock, Date only).
- Incomplete (`completed: false`) sets and in-progress sessions are ignored.
- A set corrected with `PUT /workout-sessions/{id}/corrections/sets/{clientSetId}` (inside the correction window, faked clock) changes `metTarget` and the suggestion.
- No load to lower (reps-only) → `hold` with the deload reason; deload after only 2 sessions.
- The endpoint never changes the plan (version unchanged after the call); accepting via PATCH with `baseVersion` works and a stale `baseVersion` → 409 `VERSION_CONFLICT`.
- Only the caller's sessions count.
