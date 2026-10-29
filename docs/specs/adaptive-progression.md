# Adaptive progression suggestions (contract)

`GET /workout-plans/{planId}/progression-suggestions`: read-only, rule-based suggestions to progress planned exercises, from the user's completed session history. Based on PRD §6 ("If a user consistently completes prescribed sets with manageable effort, the system may suggest a small progression. If they report pain, unusual fatigue, or excessive difficulty, it should not automatically increase load") and the safety rules in PRD §10 (sensible progressions, allow users to modify, no medical claims).

**No AI. Never automatic.** The endpoint only suggests. It never writes to the plan, the sessions or XP. The user applies a suggestion through the existing plan edit (`PATCH /workout-plans/{planId}`).

## Scope
- The plan's **latest version**; one suggestion per planned exercise (`PlannedWorkout.exercises[]` entry, identified by `plannedWorkoutId` + `exerciseId`).
- History = the user's **completed** sessions that followed this plan (any version) and logged that exercise; at most the last **N = 3** such sessions, newest first. Only `completed: true` sets count; corrections are applied (same counting as `/progress/exercises`).
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

## Increment rules (one step, never more)
| trackingType | Suggestion | Step |
|---|---|---|
| `reps_weight` with `targetWeightKg` | raise `targetWeightKg` | lower-body primary muscle (quads, hamstrings, glutes, calves): **+5%**, otherwise **+2.5%**; rounded **down** to 0.5 kg, minimum +1.0 kg (+0.5 kg when target < 10 kg) |
| `reps_weight` without a target | set `targetWeightKg` | to the heaviest weight used in all N sessions' counted sets (no increase) |
| `reps` (bodyweight) | raise `repsMin`/`repsMax` | +1 rep each (+2 when `repsMax >= 15`); when `repsMax` would exceed 20, suggest no rep change and `reason: REP_CEILING` (a harder variation is a client/user choice) |
| `duration` | raise `durationSeconds` | +10% rounded **down** to 5 s, minimum +5 s |
| `distance_duration` | raise `durationSeconds` | same as `duration` |

## Cap
- One step per suggestion; the suggested value is **at most +10%** of the current value (weight, reps or seconds) and stays within the plan schema limits (`targetWeightKg` ≤ 1000, reps ≤ 100, `durationSeconds` ≤ 7200). If the minimum step would exceed 10%, the step is the minimum step anyway only when the current value is below the small-load threshold (target < 10 kg, reps < 10, duration < 50 s); otherwise `hold` with `CAP_REACHED`.

## Cooldown
The server derives "last progression applied" from plan versions: the newest plan version where this exercise's prescribed load (target weight, reps or duration) increased versus the previous version. If its `createdAt` is less than 7 days ago, `hold` with `COOLDOWN` and `cooldownUntil`. No new table is needed.

## Deload
Suggest a **deload** (−10% of `targetWeightKg` rounded down to 0.5 kg, or −10% duration; reps unchanged for `reps`) when either:
- **two consecutive** qualifying sessions missed the prescription (rule 2) for this exercise, or
- the latest two sessions both report `perceivedDifficulty = 5`.
A deload is a suggestion like any other; it is never applied automatically. Pain or medical symptoms are **not** inferred from data; the client shows its standard "stop and seek professional advice" copy whenever the user reports pain (out of scope here).

## Response
`ProgressionSuggestions`: `{ planId, planVersion, generatedAt, windowSessions: 3, suggestions: [...] }`, one item per planned exercise, in plan order:
- `plannedWorkoutId`, `exerciseId`, `trackingType`
- `action`: `increase | hold | deload`
- `reason`: `ALL_SETS_COMPLETED` (increase) | `NOT_ENOUGH_HISTORY` | `MISSED_TARGET` | `HIGH_EFFORT` | `COOLDOWN` | `CAP_REACHED` | `REP_CEILING` | `NO_TARGET_SET` (increase that only sets a missing target) | `REPEATED_MISSES` | `VERY_HARD` (deloads)
- `current`: `{ targetWeightKg, repsMin, repsMax, durationSeconds }` (the latest plan version's values)
- `suggested`: same shape, or `null` for `hold`. Only changed fields differ from `current`.
- `cooldownUntil`: date-time or null
- `basedOn`: up to 3 items `{ sessionId, completedAt, perceivedDifficulty|null, completedSets, plannedSets, metTarget, bestReps|null, bestWeightKg|null, bestDurationSeconds|null, bestDistanceM|null }`, newest first.

## Accepting a suggestion
The client copies `suggested` into that exercise of its latest plan draft and calls `PATCH /workout-plans/{planId}` with `baseVersion = planVersion`. A stale suggestion fails with the existing `409 VERSION_CONFLICT`; the client refetches. Dismissing needs no call (v1 keeps no dismiss state).

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
- `reps` 8–12 → 9–13; 15–18 → 17–20; 19–20 → `REP_CEILING`.
- `duration` 60 s → 65 s; 300 s → 330 s.
- One session missing a set or a rep → `MISSED_TARGET`; `perceivedDifficulty` 4 in the window → `HIGH_EFFORT`.
- Two consecutive misses → deload −10%; two sessions at difficulty 5 → `VERY_HARD` deload.
- Cooldown: a version raising the load 3 days ago → `COOLDOWN` with `cooldownUntil`; 8 days ago → increase (faked clock, Date only).
- Incomplete (`completed: false`) sets and in-progress sessions are ignored; corrections are applied.
- The endpoint never changes the plan (version unchanged after the call); accepting via PATCH with `baseVersion` works and a stale `baseVersion` → 409 `VERSION_CONFLICT`.
- Only the caller's sessions count.
