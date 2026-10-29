# Profile macro targets (contract)

Adds optional daily macro targets to the profile so `/nutrition/summary` can report macro adherence.

## Fields
- `dailyProteinTargetG`, `dailyCarbsTargetG`, `dailyFatTargetG`: `integer | null`, grams per day, range **0–1000**, on `Profile` (read) and `ProfileUpdate` (`PUT /me/profile`; read with `GET /me/profile`).
- Naming matches the existing `dailyCalorieTargetKcal` / `dailyWaterTargetMl`.
- **Update semantics (`PUT /me/profile`, partial):** each field is optional. Leaving a field **out** of the body keeps the stored value; sending an explicit **`null`** clears it. Out of range or non-integer → 400 `VALIDATION_FAILED`.
- **0 is normalized to null.** A `PUT /me/profile` may send 0 (accepted range stays 0–1000), but it is stored as `null`. `GET /me/profile` and `GET /me/export` therefore never return 0 for these fields; null is the only "no target" value.
- Default for new and existing profiles: `null` (nullable columns, no backfill).

## No derivation in v1
Macro targets are **explicit only**. They are not derived from `dailyCalorieTargetKcal`, weight, or diet preference, and setting or changing the calorie target never changes them. There is no consistency check between macro grams (4/4/9 kcal) and the calorie target. A derived "suggested split" can be added later as a separate, read-only field without changing these semantics.

## `targets` object
`targets` in `/nutrition/summary` is non-null when **any** of kcal, water, protein, carbs or fat has a target, so a profile with only macro targets gets a `targets` object with `kcal` and `waterMl` null. It is `null` only when all five are unset.

## Effective date
Targets take effect **from now** and are not stored historically. Every summary period, **including past ones**, uses the current targets (same as `dailyCalorieTargetKcal` and `dailyWaterTargetMl`).

## Nutrition summary wiring
- `targets.proteinG/carbsG/fatG` = the profile's current `daily*TargetG` (null when unset).
- `proteinPct/carbsPct/fatPct` = `avgPerLoggedDay.<macro> / target × 100`, one decimal, HALF_UP, computed from the unrounded average; `null` when no food was logged or **its own** target is null. Each pct is independent: e.g. only protein set → `proteinPct` non-null, `carbsPct` and `fatPct` null. `adherence` is `null` only when every pct is null.

## Other surfaces
- `GET /me/export` includes the new fields through `Profile`.
- No change to badges, challenges or XP.

## Implementation notes
- Prisma: three nullable `Int` columns on `user_profiles` (`daily_protein_target_g`, `daily_carbs_target_g`, `daily_fat_target_g`), additive migration.
- DTO: `@IsOptional() @IsInt() @Min(0) @Max(1000)`, allowing null; the service maps 0 → null before writing.

## Planned tests
- PUT sets each target; GET returns it; omitted fields unchanged; explicit `null` clears.
- PUT with 0 → 200, stored and returned as `null` in the PUT response, `GET /me/profile` and `GET /me/export`.
- Validation: -1, 1001, 12.5 and a string → 400; 0 and 1000 accepted.
- Setting the calorie target leaves macro targets null (no derivation).
- Summary: targets echoed; pcts computed for a set target (incl. HALF_UP at .x5 and the unrounded-average case); PUT 0 → target and pct null; only protein set → `proteinPct` non-null, `carbsPct`/`fatPct` null; targets changed today apply to a past period's summary; no food logged → macro pcts null; macros set but kcal/water unset → `adherence` non-null with only macro pcts.
- Existing profiles after migration read the fields as null.
- Data export includes the fields.
- Auth guard.
