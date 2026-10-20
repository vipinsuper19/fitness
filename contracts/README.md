# contracts/

Source of truth for the API.

- `openapi.yaml`: OpenAPI 3.1, v1, served under `/api/v1`. It covers auth, profile and goals, exercises, workout plans, workout sessions, nutrition, progress and XP, plus the conventions for errors, idempotency, units, dates and ownership.
- `schemas/workout-plan-draft.schema.json`: JSON Schema (2020-12) for plan drafts. The MVP fills drafts with a rule-based generator. Any later AI provider must return this exact shape, and the server validates it before use.
- `redocly.yaml`: lint rules.

## Conventions
- Errors: `{ code, message, details?, requestId }`. Clients branch on `code`.
- Writes accept `Idempotency-Key` (UUID). It is required on session start, set logs, session completion, food, water and measurement logs. Replays return the stored response with `Idempotent-Replayed: true`.
- XP is awarded only by the server, when a workout session is completed. `/xp` is read-only.
- Units: metric (kg, cm, ml, kcal, g). Imperial is a display preference only. India first: timezone `Asia/Kolkata`, locale `en-IN`, currency `INR`. Food servings include katori, plate and glass.
- `null` means "no data" and is never 0.

## Checks
`cd backend && npm run contract:lint && npm test` (`test/contract.spec.ts`). CI runs both whenever `contracts/**` or `backend/**` changes.
