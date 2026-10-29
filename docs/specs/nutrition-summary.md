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

- The profile stores only the **current** `dailyCalorieTargetKcal` and `dailyWaterTargetMl`. There is no target history and there are no macro targets.
- `targets` are therefore the current targets, applied to every period. `proteinG`, `carbsG` and `fatG` are always `null` until the profile gains macro targets. `targets` is `null` when neither calories nor water has a target.
- Adherence, each × 100 with one decimal HALF_UP:
  - `kcalPct` = `avgPerLoggedDay.energyKcal / targets.kcal`
  - `proteinPct`, `carbsPct`, `fatPct` = `avgPerLoggedDay.<macro> / targets.<macro>`
  - `waterPct` = `avgWaterMlPerLoggedDay / targets.waterMl`

  `kcalPct`, `proteinPct`, `carbsPct` and `fatPct` are `null` when no food was logged in the period or their target is `null`. `waterPct` is `null` when no water was logged or `targets.waterMl` is `null`. A target of 0 is treated as `null`. `adherence` is `null` only when every pct is `null`.
- **Macro targets:** `targets.proteinG`, `carbsG` and `fatG` come from the profile's `dailyProteinTargetG`, `dailyCarbsTargetG` and `dailyFatTargetG` (see [macro-targets.md](macro-targets.md)); the macro pcts follow the rules above (each pct is null when its own target is null, independently; PATCH 0 is stored as null; current targets apply to every period, including past ones).
- If targets are stored with history later, each day should use the target in effect that day. That would be an additive change, documented then.

## Rounding

- Values are summed from the stored one-decimal snapshots, then rounded once at the end. Nutrients and averages use one decimal place, HALF_UP (BigDecimal/`Decimal` in code, not float `Math.round`). Percentages use one decimal place, HALF_UP. `waterMl` is an integer sum.

## Soft-deleted foods

- A food log snapshots its nutrients at log time, so logs of a food that was later soft-deleted (`foods.deleted_at`) still count with their snapshot values. Deleting the food does not rewrite the diary. Deleted food logs (the entries themselves) are gone and do not count.

## Implementation notes (for the backend PR)

- Aggregate in SQL: group food logs by `date` and water logs by `date`, then fold the days into periods in code. Don't load raw entries for long ranges.
- Planned tests: day/week/month clipping at both edges (a range starting on a Wednesday, a month boundary), null vs 0, `avgPerLoggedDay` using `daysLogged`, adherence nulls (no food, no water, no target, a target of 0, and `adherence` null only when every pct is null), a soft-deleted food still counted, HALF_UP at .x5, the 366-day limit, and the auth guard.
