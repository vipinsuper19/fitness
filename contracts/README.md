# contracts/

Source of truth for the API: `openapi.yaml` (v1, `/api/v1`) plus JSON schemas for AI outputs (task T5).
Conventions: errors are `{ code, message, details?, requestId }`; write endpoints accept `Idempotency-Key`.
Backend checks its generated Swagger against this spec; Android builds its client from it.
