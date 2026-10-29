# Nutrition summary (P0 progress, PRD 5.6)

`GET /nutrition/summary?from&to&bucket=day|week|month` gives totals, averages and target adherence over a range. It is read-only: no XP, no Idempotency-Key. The contract is in `contracts/openapi.yaml` (`NutritionSummary`).

## Range and periods

- `from`, `to` and every `period` are ISO dates (`YYYY-MM-DD`, OpenAPI `format: date`).

- Same validation as `/progress/summary`: `from ≤ to`, at most 366 days, years 1900–2100, otherwise `400 VALIDATION_FAILED`. `bucket` defaults to `day`.
- Local dates are in the profile timezone (default `Asia/Kolkata`), echoed as `timezone`. Food and water logs carry a local `date` already.
- `week`: Monday to Sunday. `month`: calendar month. The first and last periods are clipped to `from`/`to`. `period` is the first date of the clipped period, and `daysInPeriod` counts its days inside the range.
- Every period is returned, in date order, including empty ones.

## Values

- `totals`: the sum of the period's food logs (`energyKcal`, `proteinG`, `carbsG`, `fatG`, `fiberG`, `sugarG`, `sodiumMg`), using the same per-entry nutrients as `/nutrition/daily`, so a day's numbers match the daily view.
- `avgPerLoggedDay` = `totals / daysLogged`, where `daysLogged` counts days with at least one food log. Dividing by every day would make unlogged days look like fasting.
- Optional nutrients follow `DailyNutrition`: `entriesMissing` counts entries without a value. A total is `null` when every entry lacks the nutrient, and partial otherwise.
- `waterMl` is the period's **total** water. `waterDaysLogged` counts days with at least one water log, and `avgWaterMlPerLoggedDay` = `waterMl / waterDaysLogged` (integer, HALF_UP; `null` when no water was logged). `null` means nothing was logged, never 0. The same goes for `totals` and `avgPerLoggedDay` when no food was logged (see Targets for when `adherence` is `null`).

## Targets

- **Dated history (supersedes the old "current targets for every period" rule):** the five daily targets are kept as dated history ([target-history.md](target-history.md)). Each day uses the targets **in effect that day** (the newest history row on or before it; none → all null).
- Top-level `targets` are the targets in effect on `to`. Each point also has `targets` (in effect on its last date) and `targetsChanged` (true when the target in effect (any of the five) differs between any two calendar dates in the period (bucket), within `from`..`to`). `proteinG`, `carbsG` and `fatG` are the macro targets in effect on that date (`daily*TargetG` history, see [macro-targets.md](macro-targets.md) and [target-history.md](target-history.md)), `null` when unset. `targets` is `null` only when none of calories, water, protein, carbs or fat has a target (only macros set → non-null with `kcal`/`waterMl` null).
- Adherence, each × 100 with one decimal HALF_UP, as a **ratio of sums over the days that count** (logged days whose own target for that value is non-null):
  - `kcalPct` = Σ `energyKcal` ÷ Σ that day's kcal target, over food-logged days with a kcal target
  - `proteinPct`, `carbsPct`, `fatPct`: the same, per macro
  - `waterPct`: the same, over water-logged days with a water target

  With a constant target this equals `avgPerLoggedDay / target`, exactly as before. `kcalPct`, `proteinPct`, `carbsPct` and `fatPct` are `null` when no counted day exists (no food logged, or no logged day with that target). `waterPct` is `null` when there is no water-logged day with a water target. A target of 0 is treated as `null`. `adherence` is `null` only when every pct is `null`.
- **Client local date:** periods use the local `date` the client sends on each food and water log. The profile timezone is only echoed back as `timezone` and is not used to re-bucket logs, so the client must send the correct local date for logs made near midnight.
- **Unrounded pcts:** the pcts are computed from the unrounded average, so a pct may not exactly match the displayed (rounded) average divided by the target.
- **Water-only days:** a day with only water logged counts in `waterDaysLogged` but not in `daysLogged`.
- **Macro targets:** `targets.proteinG`, `carbsG` and `fatG` come from the profile's `dailyProteinTargetG`, `dailyCarbsTargetG` and `dailyFatTargetG` (see [macro-targets.md](macro-targets.md)); the macro pcts follow the rules above (each pct is null when its own target is null, independently; 0 sent on PUT /me/profile is stored as null; each day uses its own target in effect, see [target-history.md](target-history.md)).

## Rounding

- Values are summed from the stored one-decimal snapshots, then rounded once at the end. Nutrients and averages use one decimal place, HALF_UP (BigDecimal/`Decimal` in code, not float `Math.round`). Percentages use one decimal place, HALF_UP. `waterMl` is an integer sum.

## Soft-deleted foods

- A food log snapshots its nutrients at log time, so logs of a food that was later soft-deleted (`foods.deleted_at`) still count with their snapshot values. Deleting the food does not rewrite the diary. Deleted food logs (the entries themselves) are gone and do not count.

## Implementation notes (for the backend PR)

- Aggregate in SQL: group food logs by `date` and water logs by `date`, then fold the days into periods in code. Don't load raw entries for long ranges.
- Planned tests: day/week/month clipping at both edges (a range starting on a Wednesday, a month boundary), null vs 0, `avgPerLoggedDay` using `daysLogged`, adherence nulls (no food, no water, no target, a target of 0, and `adherence` null only when every pct is null), a soft-deleted food still counted, HALF_UP at .x5, the 366-day limit, and the auth guard.
