# Profile macro targets (contract)

Adds optional daily macro targets to the profile so `/nutrition/summary` can report macro adherence.

## Fields
- `dailyProteinTargetG`, `dailyCarbsTargetG`, `dailyFatTargetG`: `integer | null`, grams per day, range **0–1000**, on `Profile` (read) and `ProfileUpdate` (`PATCH /profile`).
- Naming matches the existing `dailyCalorieTargetKcal` / `dailyWaterTargetMl`.
- Each is optional in an update; omitting a field leaves it unchanged, `null` clears it. Out of range or non-integer → 400 `VALIDATION_ERROR`.
- **0 or null means no target.** 0 is accepted and stored, but treated as null everywhere targets are used.
- Default for new and existing profiles: `null` (nullable columns, no backfill).

## No derivation in v1
Macro targets are **explicit only**. They are not derived from `dailyCalorieTargetKcal`, weight, or diet preference, and setting or changing the calorie target never changes them. There is no consistency check between macro grams (4/4/9 kcal) and the calorie target. A derived "suggested split" can be added later as a separate, read-only field without changing these semantics.

## Nutrition summary wiring
- `targets.proteinG/carbsG/fatG` = the profile's current `daily*TargetG`, with 0 mapped to `null` (targets are not stored historically, as for kcal and water).
- `proteinPct/carbsPct/fatPct` = `avgPerLoggedDay.<macro> / target × 100`, one decimal, HALF_UP, computed from the unrounded average; `null` when no food was logged or the target is null/0. `adherence` is `null` only when every pct is null.

## Other surfaces
- `GET /me/export` includes the new fields through `Profile`.
- No change to badges, challenges or XP.

## Implementation notes
- Prisma: three nullable `Int` columns on `user_profiles` (`daily_protein_target_g`, `daily_carbs_target_g`, `daily_fat_target_g`), additive migration.
- DTO: `@IsOptional() @IsInt() @Min(0) @Max(1000)`, allowing null.

## Planned tests
- PATCH sets each target; GET returns it; omitted fields unchanged; `null` clears.
- Validation: -1, 1001, 12.5 and a string → 400; 0 and 1000 accepted.
- Setting the calorie target leaves macro targets null (no derivation).
- Summary: targets echoed; pcts computed for a set target (incl. HALF_UP at .x5 and the unrounded-average case); target 0 → target and pct null; no food logged → macro pcts null; macros set but kcal/water unset → `adherence` non-null with only macro pcts.
- Existing profiles after migration read the fields as null.
- Data export includes the fields.
- Auth guard.
