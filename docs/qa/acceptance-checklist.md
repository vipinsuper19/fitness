# Fitness MVP — acceptance checklist

Status: ✅ automated & passing on main 886dd84 (+ QA suite) · ⏳ needs Android UI · ☐ manual

| # | Area | Criterion | Evidence | Status |
|---|---|---|---|---|
| A1 | Auth | Register/login with email+password (argon2); duplicate email → 409 EMAIL_TAKEN | auth.int-spec | ✅ |
| A2 | Auth | Access token 15 min; refresh rotates; reused refresh revokes family | auth.int-spec, qa e2e | ✅ |
| A3 | Auth | Every user route returns 401 without/with invalid/with refresh token (37 routes) | qa e2e | ✅ |
| A4 | Auth | Account deletion needs password, erases data, kills tokens | auth.int-spec, qa e2e | ✅ |
| A5 | Auth | Login/refresh throttled | hardening.e2e-spec | ✅ |
| B1 | Isolation | Other user's goal/plan/session/food/log/water/measurement → 404 on read & write | qa e2e, per-module specs | ✅ |
| B2 | Isolation | Custom foods, history, daily totals, XP and export never show other users' data | qa e2e | ✅ |
| C1 | Retries | Same Idempotency-Key + body replays, one row | qa e2e, sessions/nutrition specs | ✅ |
| C2 | Retries | Same key, different body → 409 IDEMPOTENCY_KEY_REUSED | qa e2e | ✅ |
| C3 | Retries | Concurrent duplicates → exactly one row | qa e2e | ✅ |
| C4 | Retries | Keys scoped per user | qa e2e | ✅ |
| C5 | Retries | Set logs upsert by client set UUID | sessions.int-spec | ✅ |
| D1 | XP | XP only on session completion; empty session → 422 | sessions.int-spec | ✅ |
| D2 | XP | Concurrent/repeated completes → one ledger row | qa e2e | ✅ |
| D3 | XP | XP not client-writable; no ledger write endpoint | qa e2e | ✅ |
| D4 | XP | Discard reverses XP | sessions.int-spec | ✅ |
| E1 | Plans | Generate returns draft, never persists; save, activate, active | plans.int-spec, qa e2e | ✅ |
| E2 | Plans | PATCH creates new version; stale baseVersion → 409 | plans.int-spec | ✅ |
| F1 | Nutrition | Food search, custom food, food logs with override, water, daily totals | nutrition.int-spec | ✅ |
| G1 | Progress | Measurements CRUD; summary null = missing, not 0; streak | progress.int-spec | ✅ |
| H1 | Export | GET /me/export returns only own data, rate-limited | export.int-spec, qa e2e | ✅ |
| I1 | Contract | Responses match openapi.yaml; errors carry requestId | contract.spec | ✅ |
| J1 | Android | Onboarding resumable, units kg/lb cm/ft-in | — | ⏳ |
| J2 | Android | Workout tracking incl. offline draft sync | — | ⏳ |
| J3 | Android | Food diary, progress charts, dashboard empty states | — | ⏳ |
| K1 | Ops | Staging on Render cold start tolerated by app (loading/retry UI) | — | ☐ |
| K2 | Ops | No secrets in repo; no Play upload | repo scan | ☐ |
