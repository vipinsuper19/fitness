# Suggested macro split (contract)

A read-only, rule-based suggestion for the daily macro targets added in [macro-targets.md](macro-targets.md). No AI and no paid service. It never changes stored targets: macro targets stay **explicit**, and the user applies a suggestion with `PUT /me/profile`.

## Endpoint
`GET /me/macro-suggestion` (JWT auth guard, like every `/me` route) → 200 `MacroSuggestion`; no token or an invalid one → 401. There are no other errors, and the response is always 200, with `suggestion: null` and `missingInputs` listing every missing input when it can't be computed. `missingInputs` is `[]` exactly when `suggestion` is present.

It is a separate endpoint, not a field on `GET /me/profile`, because:
- it reads goals as well as the profile;
- `Profile` stays the stored shape (it is also the `PUT` response and part of the export);
- the formula can be versioned (`formulaVersion`) without touching `Profile`.

It **never writes** and has no side effects (no XP, no `updatedAt` bump), so there is no `Idempotency-Key`.

The suggestion is **not part of `GET /me/export`**: it is derived, not stored, and the export already contains the profile and goals it is computed from.

```json
{
  "suggestion": { "proteinG": 112, "carbsG": 262, "fatG": 56, "kcal": 2000 },
  "missingInputs": [],
  "basis": { "calorieTargetKcal": 2000, "weightKg": 70, "goal": "lose_weight", "goalSource": "active_goal",
             "dietPreference": "vegetarian", "proteinGPerKg": 1.6, "fatPct": 25 },
  "formulaVersion": 1
}
```

## Inputs
- `K` = `profile.dailyCalorieTargetKcal` (800–6000). If it is null, `missingInputs` contains `CALORIE_TARGET`.
- `W` = `profile.weightKg` (20–400). If it is null, `missingInputs` contains `WEIGHT`.
- `missingInputs` lists **every** missing input, always in the order `CALORIE_TARGET`, `WEIGHT`, so the UI can ask for both at once. When it is non-empty, `suggestion` is null; when it is empty, `suggestion` is present.
- **Goal:** goals are a list, not a profile field. Use the **newest active** goal (`status = active`, latest `createdAt`) whose type is `lose_weight`, `gain_muscle`, `maintain` or `improve_endurance`. `workouts_per_week` is ignored. If there is none, use `maintain` with `goalSource: "default"`.
- `dietPreference` is echoed in `basis` but **does not change the numbers in v1** (see below).

`basis` is **always filled in**, including when `suggestion` is null: `calorieTargetKcal` / `weightKg` are null for the missing input(s), and `goal`, `goalSource`, `dietPreference`, `proteinGPerKg` and `fatPct` are always set, so the client can say what's missing and what would be used.

## Formula (v1)
| Goal | Protein g/kg |
|---|---|
| `lose_weight` | 1.6 |
| `gain_muscle` | 1.8 |
| `improve_endurance` | 1.4 |
| `maintain` / default | 1.2 |

1. **Protein:** `P = round(W × perKg)`, then `P = min(P, floor(0.30 × K / 4))`. Protein is capped at 30% of kcal.
2. **Fat:** `F = max(round(0.25 × K / 9), round(0.6 × W))` (25% of kcal, but at least 0.6 g/kg), then `F = min(F, floor(0.35 × K / 9))` (at most 35% of kcal).
3. **Carbs:** `C = floor((K − 4P − 9F) / 4)`, the remainder. Because of the caps, protein and fat are at most 65% of kcal, so `C ≥ 0.35 × K / 4 − 1` and is never negative.
4. `kcal = 4P + 4C + 9F`: the **sum after rounding**, not the target `K` (that is `basis.calorieTargetKcal`) (≤ `K`; at most 3 kcal under it because carbs are floored, **except** when step 5 caps carbs, which can drop far more: 401 kcal in example F).

`round` is half-up to a whole gram. `W` is first rounded half-up to 0.1 kg (the profile stores 2 decimals).

5. **Range cap:** every gram value must fit the profile's 0–1000 target range. Protein is at most 450 g (30% of 6000 kcal) and fat at most 233 g, but carbs can exceed 1000 g at a high `K` with a low `W` (for example `K = 6000`, `W = 20`). So `C = min(C, 1000)`; the dropped kcal shows as a lower `kcal`.

## India-first reasoning
- **Higher-carb defaults:** Indian diets are typically cereal- and pulse-based (rice, roti, dal), and much of the population is vegetarian. Carbs as the remainder (usually 45–60% of kcal) fits that eating pattern better than a low-carb default, and is in line with ICMR-NIN guidance that most energy comes from carbohydrate, with fat around 20–30%.
- **Protein cap at 30% of kcal:** this keeps vegetarian and Jain users (dal, paneer, curd and soy, with no eggs or meat) at a target they can realistically reach. For example, 90 kg on 1500 kcal would otherwise be asked for 162 g.
- **Fat at 25%** is a middle value that leaves room for cooking oil and ghee, with a 0.6 g/kg floor for low calorie targets.
- **Diet preference doesn't change the numbers in v1.** Protein need doesn't depend on diet type; only the food sources do. The client can show a hint for vegetarian, vegan and Jain users (for example, "dal, paneer, curd, soy"). Lower targets per diet type would be a v2 decision.

## Worked examples (planned tests)
| # | K | W | Goal | P | F | C | kcal | Notes |
|---|---|---|---|---|---|---|---|---|
| A | 2000 | 70 | lose_weight (active goal) | 112 | 56 | 262 | 2000 | P = 112 (cap 150); F = max(56, 42) = 56 (cap 77); C = 1048/4 |
| B | 1500 | 90 | gain_muscle | 112 | 54 | 141 | 1498 | P = 162, capped to floor(112.5) = 112; F = max(42, 54) = 54 (cap 58); C = floor(566/4) |
| C | 2400 | 60 | none → maintain (`default`) | 72 | 67 | 377 | 2399 | F = max(round(66.67) = 67, 36); C = floor(1509/4) |
| D | 2800 | 75 | improve_endurance | 105 | 78 | 419 | 2798 | F = round(77.78) = 78; C = floor(1678/4) |
| E | 1200 | 80 | maintain | 90 | 46 | 106 | 1198 | P = 96, capped to 90; F = max(33, 48) = 48, capped to floor(46.67) = 46; C = floor(426/4) |
| F | 6000 | 20 | maintain | 24 | 167 | 1000 | 5599 | C = floor(4401/4) = 1100, capped to 1000; kcal is 401 under `K` |
| G | 2700 | 180 | maintain | 202 | 105 | 236 | 2697 | P = 216, capped to floor(8100/40) = 202; F = max(75, 108), capped to floor(18900/180) = 105 (float `0.35*2700/9` floors to 104) |
| H | 2500 | 87.5 | improve_endurance | 123 | 69 | 346 | 2497 | P = round(875×14/100) = round(122.5) = 123 (float `87.5*1.4` = 122.4999… rounds to 122); F = max(69, round(52.5) = 53) = 69 |

## Other planned tests
- `dailyCalorieTargetKcal` null (weight set) → `suggestion: null`, `missingInputs: ["CALORIE_TARGET"]`, `basis.calorieTargetKcal: null`.
- `weightKg` null (kcal set) → `suggestion: null`, `missingInputs: ["WEIGHT"]`.
- Both null → `suggestion: null`, `missingInputs: ["CALORIE_TARGET", "WEIGHT"]`.
- Both set → `missingInputs: []` and `suggestion` non-null. Every example also asserts `suggestion.kcal == 4P + 4C + 9F`, which differs from `K` in B–F.
- Goal choice:
  - Two active goals (`gain_muscle` older, `lose_weight` newer) → `lose_weight`.
  - Only `workouts_per_week` active → `maintain` / `default`.
  - An achieved or abandoned `lose_weight` goal is ignored.
- `dietPreference` vegan vs non_vegetarian → identical numbers, and `basis.dietPreference` is echoed.
- Never writes: the profile's `daily*TargetG` and `updatedAt` are unchanged after the GET. Applying the suggestion with `PUT /me/profile` stores exactly those grams.
- Results are always integers within 0–1000 (example F).
- 401 without a token, and another user's data never affects the result.
- Contract: the response validates against `MacroSuggestion`.

## Implementation notes
- **Whole-number maths only.** JS floats give the wrong floor for the fat cap at K = 1440, 2700, 2880, 5220, 5400, 5580 and 5760 (e.g. `0.35*2700/9` = 104.999…), and `87.5*1.4` rounds to 122. With `w` = weight in tenths of a kg (integer) and `pk` = protein g/kg × 10 (16/18/14/12), use only integer `+ − ×` and floor division:
  - `P = min(floor((w·pk + 50) / 100), floor(3K / 40))`
  - `F = min(max(floor((K + 18) / 36), floor((6w + 50) / 100)), floor(7K / 180))`
  - `C = min(floor((K − 4P − 9F) / 4), 1000)`
  - `w = floor((weightKg × 100 + 5) / 10)` computed from the Decimal's exact digits, not a float.
- A pure function in `macro-suggestion.rules.ts` (unit-tested with the table above), plus a small controller and service under `me/`. No migration.
