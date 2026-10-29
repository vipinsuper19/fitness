# Dated target history (contract)

Daily targets (`dailyCalorieTargetKcal`, `dailyWaterTargetMl`, `dailyProteinTargetG`, `dailyCarbsTargetG`, `dailyFatTargetG`) are kept as **dated history**, so each day's nutrition numbers use the targets that were in effect **that day**. Rule-based; no AI. This **supersedes** the "current targets apply to every period, including past ones" rule in [nutrition-summary.md](nutrition-summary.md) and [macro-targets.md](macro-targets.md).

## Storage
Table `profile_target_history`:

| column | type | notes |
|---|---|---|
| `user_id` | uuid | FK → users, `ON DELETE CASCADE` |
| `effective_from` | date | local date in the profile timezone |
| `daily_calorie_target_kcal`, `daily_water_target_ml`, `daily_protein_target_g`, `daily_carbs_target_g`, `daily_fat_target_g` | int, nullable | a **full snapshot** of all five (not just the changed one); 0 is stored as null, as on the profile |
| `created_at`, `updated_at` | timestamptz | |

Primary key `(user_id, effective_from)`: at most one row per user per local date.

- **The targets in effect on date `d`** are the row with the greatest `effective_from ≤ d`. With no such row, all five are null for `d` (the user had no targets yet).
- The `user_profiles` columns stay as they are and remain the source for `GET /me/profile`. History is written alongside them.

## Writes (`PUT /me/profile`)
- When a `PUT /me/profile` **changes the value** of any of the five targets (after 0 → null), the same transaction **upserts today's row** with the full new snapshot. "Today" is the local date in the profile timezone, using the timezone **after** this PUT is applied (if the same PUT changes `timezone`).
- Several changes on the same local day **replace** that day's row, so the last write of the day wins. The day's numbers use the final targets of that day.
- A PUT that leaves the five target values unchanged (fields omitted, or the same value re-sent) writes nothing. An idempotent replay writes nothing either.
- No other endpoint writes history. `GET /me/macro-suggestion` never writes.
- Example: targets 2000 kcal until Oct 10, then the user changes them to 1800 kcal on Oct 10 at 09:00 IST. The row `2026-10-10 → 1800` makes Oct 10 itself use 1800, even though food was logged before 09:00 that day.

## Backfill
- Every existing profile with **at least one** non-null target gets one row with `effective_from = 1900-01-01` (the earliest date the summary accepts) holding its current five values. Past periods are therefore **unchanged at migration time**.
- Profiles with no targets get no row, which reads as all-null, the same as today.
- New users get no backfill row. Their first target-setting PUT writes a row for that day, and days before it have no targets.

## Reads
### `GET /nutrition/summary`
Food and water logs already carry a local `date`. Each logged day uses its own targets.

- **Adherence is the ratio of sums over the days that count:**
  - `kcalPct` = Σ `energyKcal` on food-logged days whose kcal target is non-null ÷ Σ of those days' kcal targets × 100.
  - `proteinPct`, `carbsPct` and `fatPct` work the same way, with each macro's own target.
  - `waterPct` uses water-logged days whose water target is non-null.

  This equals "average intake ÷ the average of each logged day's own target". With a constant target it is exactly today's `avgPerLoggedDay / target × 100`, so existing numbers don't move. One decimal, HALF_UP, from unrounded sums.
- A pct is `null` when no counted day exists: no logs, or no day in the period with that target set.
- Logged days without that target are left out of that pct only. `avgPerLoggedDay` and `daysLogged` are unchanged and still cover every logged day.
- Unlogged days never count; they don't add a target to the denominator.
- **Why the ratio of sums, not the mean of daily pcts:** a mean of per-day pcts lets one tiny-target or near-empty day swing the period. The ratio of sums matches today's formula exactly when targets don't change.
- **Per point:** new `targets` (`NutritionTargets | null`) holds the targets in effect on the period's **last date**, and `targetsChanged` (boolean) is true when any target differs between days of the period. Clients show a "target changed during this period" hint when it is true.
- **Top-level `targets`** is now the targets in effect on `to`, no longer "current". It is still `null` when all five are null on `to`.

### `GET /nutrition/daily`
`calorieTargetKcal` and `waterTargetMl` are the targets in effect on `date`.

### `GET /me/target-history` (new, read-only)
- Returns `{ items: TargetHistoryEntry[] }`, newest `effectiveFrom` first, with no paging. There is at most one row per day the targets changed, so the list stays small. The server caps it at 1000 rows with a note.
- A backfilled row shows `effectiveFrom: "1900-01-01"`, which clients display as "since you joined".
- **No editing in v1.** There is no POST, PATCH or DELETE, and past entries can't be changed. To change targets, use `PUT /me/profile`, which affects today onwards. Back-dated edits would silently rewrite past adherence and badges, so they are a possible v2 feature.

## Export
`GET /me/export` gains `targetHistory: TargetHistoryEntry[]` (newest first, the same shape as the endpoint). Account deletion removes it through the cascade.

## Migration plan
1. **Migration (additive):**
   - `CREATE TABLE profile_target_history` with its PK and FK.
   - In the same migration, an **idempotent** backfill: `INSERT … SELECT user_id, DATE '1900-01-01', <five columns> FROM user_profiles WHERE <any non-null> ON CONFLICT (user_id, effective_from) DO NOTHING`.
   - No changes to existing columns, and nothing is dropped.
2. **Deploy the app** that writes history on PUT and reads it in the summary, daily view and export.
3. **Reconcile:** run the idempotent `npm run targets:backfill` once after the app is live. Old app instances may have handled target PUTs between steps 1 and 2 without writing history. For each profile whose newest history row differs from its current five values, or that has targets but no row, it upserts a row (`1900-01-01` when there is no row, otherwise today in the profile timezone). Running it again is a no-op.

Rollback: the old app ignores the table, so rolling back the app is safe. The table can stay in place.

## Effects on existing docs
- **nutrition-summary.md:** the "Targets" section now uses the targets in effect each day. The "if targets are stored with history later" note is resolved by this spec.
- **macro-targets.md:** the "Effective date" rule is replaced by the targets taking effect from today's local date. The planned test "targets changed today apply to a past period" is inverted.
- **openapi.yaml:** `NutritionTargets`, `NutritionAdherence`, `NutritionSummary.targets` and `DailyNutrition` targets are reworded. `NutritionSummaryPoint.targets` and `targetsChanged`, `DataExport.targetHistory`, `TargetHistoryEntry` and `GET /me/target-history` are new. The new response fields are documented as always present once implemented. They are not yet in `required`, so the current backend still validates. The implementation PR adds them to `required`.

## Planned tests
- **PUT and history:**
  - A PUT changing kcal writes today's row with all five values.
  - A second change the same day replaces the row (one row, last values).
  - A change on the next (faked) day adds a row.
  - A PUT with no target change, the same value, or only a non-target field writes nothing.
  - 0 is stored as null.
  - An Idempotency-Key replay writes nothing.
- **Timezone:** at 23:30 UTC on Oct 9, an `Asia/Kolkata` user's row is dated Oct 10. A PUT that changes `timezone` and kcal together uses the new zone. Use a faked clock (Date only).
- **Backfill:**
  - A user with targets gets exactly one `1900-01-01` row equal to the profile.
  - A user with no targets gets no row.
  - Re-running the backfill is a no-op.
  - `targets:backfill` after a simulated old-app PUT adds today's row once.
  - Past summaries are identical before and after the migration.
- **Summary:**
  - 2000 kcal until the 10th, then 1800: a `day` bucket uses each day's own target.
  - A `week` straddling the change gives `kcalPct` = Σ intake ÷ Σ targets of the logged days, `targetsChanged: true`, and `targets` equal to the last day's.
  - A constant target matches the old formula exactly (regression).
  - A logged day before the user's first target is excluded from the pct but still counted in `avgPerLoggedDay`.
  - A period with no day that has the target gives a null pct.
  - Water uses `waterDaysLogged` days only.
  - Top-level `targets` uses `to`.
  - HALF_UP is computed from unrounded sums.
- **Daily view:** `/nutrition/daily` for a past date shows that day's targets.
- **History endpoint:** newest first; 401 without a token; another user's rows are never visible; POST, PATCH and DELETE give 404/405.
- **Export:** includes `targetHistory` and validates against `DataExport`. Account deletion removes the rows.
- **Contract:** responses validate against the updated schemas.

## Implementation notes
- The migration needs a unique prefix (timestamp after the latest).
- Summary: load the user's history rows with `effective_from ≤ to` once, newest first. Resolve each day's targets in code with one forward walk over the range (at most 366 days). No per-day queries.
- Decimal maths and HALF_UP as before.

## Open questions
1. **"Today" when a PUT also changes `timezone`:** this spec uses the new zone. Is that right, or should it use the old one?
2. **Top-level `summary.targets`:** this spec uses the targets in effect on `to`. Should it stay "current" for UI compatibility, with clients reading per-point `targets` instead?
3. **Badges and challenges:** are there any that use target adherence? None today; if added later, they should use the day's own target.
4. **Back-dated editing of history in v2:** if it's added, should it re-evaluate anything derived?
5. **Pre-history days for new users** (logged before the first target): this spec excludes them from pcts. The alternative is to apply the first target back to account creation.
