# Session correction (PRD 5.3)

PRD 5.3: "Users can correct accidental logs; associated XP and challenge totals must also be updated." This spec records the decision (Tech Lead and Android Developer review, 2026-10-21). The contract is in `contracts/openapi.yaml`.

## Endpoints

Corrections live on their own paths. Each one needs an Idempotency-Key, works on completed sessions only, and always returns `200 SessionCorrectionResult {session, xpDelta, xpBalance, newBadges?}`.

| Call | Use |
| --- | --- |
| `PUT /workout-sessions/{id}/corrections/sets/{clientSetId}` | Upsert by `clientSetId`: edits the existing set in place, or adds one if none has that id. Never creates a duplicate. Body is `SetLogUpsert` (its `clientSetId` must match the path). |
| `DELETE /workout-sessions/{id}/corrections/sets/{clientSetId}` | Delete a set. |
| `PATCH /workout-sessions/{id}/completion` | Fix `completedAt`, `perceivedDifficulty` and `notes`. A new `completedAt` must be on the same local date (profile timezone) as the current one, no later than it, and after `startedAt` (`400 VALIDATION_FAILED` otherwise). |
| `DELETE /workout-sessions/{id}` | Unchanged: discard a session, completed or not. Still `204` with no body. |

`POST /workout-sessions/{id}/sets` is unchanged and still returns `409 SESSION_COMPLETED` on completed sessions.

## Errors

- `409 SESSION_NOT_COMPLETED`: a correction call on a session that isn't completed.
- `409 CORRECTION_WINDOW_CLOSED`: the session's `completedAt` local date (profile timezone) is before yesterday. Applies to the corrections paths and to `PATCH /completion`, not to discard.
- `422 SESSION_EMPTY`: the correction would leave no completed set. The client offers discard (`DELETE /workout-sessions/{id}`) instead.
- `404 SET_NOT_FOUND`: delete of a set that isn't in the session, e.g. a delete repeated with a new key. The outbox can drop it. An unknown session stays `404 NOT_FOUND`.
- Same Idempotency-Key: the original status and body are replayed.

## Decisions

1. **XP:** the server recomputes `workoutXp(completedSets, activeSeconds)`. The difference from the session's current net XP is written as a new ledger row, or none when the difference is 0. The client never writes XP. Corrections run in one transaction under the session row lock.
2. **Discard:** writes minus the session's current net XP, so the net becomes 0. The award row stays, so the session can never be awarded again.
3. **Never revoked:** earned badges and completed challenges, with their bonus XP, stay (badges spec §9).
4. **Active challenges:** progress is recomputed from source data, so it can go down or up.
5. **Re-evaluation:** every correction triggers badge and challenge evaluation. Badges newly earned through it come back in `newBadges`, which uses the same `Badge` schema as `GET /badges`.
6. **completedAt edits:** the new value stays on the same local date as the current one and can only move earlier (still after `startedAt`), so a correction can never move a workout into another day, week or challenge window (e.g. Sunday 23:30 to Monday, or back), and never adds active time.

## Ledger design

`xp_transactions` is unique on `(user_id, source_type, source_id)`. Today `XpService.reverse` writes a discard as `source_type = adjustment`, `source_id = <award id>`. A second correction, or a correction followed by that discard, would reuse that key and fail with a 500. So:

- **New table `session_corrections`:** `id uuid pk`, `session_id` (FK, no cascade on the ledger side), `user_id`, `kind` enum `correction | discard`, `xp_delta int`, `idempotency_key text`, `created_at timestamptz`. Unique on `(user_id, idempotency_key)`, with an index on `session_id`.
- **New `XpSourceType` value `session_correction`.** Each correction and each discard inserts one `session_corrections` row. When `xp_delta ≠ 0` it also inserts one ledger row with `source_type = session_correction`, `source_id = session_corrections.id` and `amount = xp_delta`. Every row has its own key, so there are no collisions. The ledger stays append-only.
- **Net session XP** = award amount + the sum of the session's `session_correction` deltas + any legacy `adjustment` row whose `source_id` is the award id. It is never below 0: a delta is clamped to `-net`.
- **Backward compatibility:** discards already in the ledger (`adjustment`, `source_id` = award id) stay as they are and count in the net. New discards use the `session_correction` path, so `XpService.reverse` is no longer used for sessions. Anything that asks "was this award reversed?" (e.g. `/progress/summary` `xpEarned`, completing again) checks for either a legacy adjustment or a `discard` correction. The `DELETE /workout-sessions/{id}` response stays `204` with no body. `/xp/ledger` shows the new rows with `sourceType` `session_correction`, so clients must accept unknown source types.
- **Session deletion:** a discard still deletes the session and its sets. The `session_corrections` and ledger rows stay, so `session_id` there isn't enforced as a foreign key (or uses `ON DELETE SET NULL`, keeping a copy of the id).

## Planned tests

- Two corrections, then a discard, on one session: three `session_correction` ledger rows, net session XP 0, no 500, and the balance equals the earlier balance minus the original award.
- A correction replayed with the same Idempotency-Key: one row, same body.
- A correction that would push net XP below 0 is clamped, and net stays 0.
- A legacy discard (`adjustment` row) is still read as reversed, and completing again doesn't re-award.
- Errors: `CORRECTION_WINDOW_CLOSED`, `SESSION_NOT_COMPLETED`, `SESSION_EMPTY`, and `SET_NOT_FOUND` on a repeated delete.
- `completedAt`: moving it later, or from yesterday to today, is `400`. With an active weekly challenge, Sunday 23:30 → Monday and Monday → Sunday are both `400`, and the challenge count is unchanged (no double count).

## Implementation notes

- Migrations `20261021000400_session_corrections` (table, `SessionCorrectionKind`, `XpSourceType.session_correction`) and `20261021000500_session_correction_amount_check` (the ledger amount check also allows negative `session_correction` rows; separate so the new enum value is committed first).
- `PATCH /completion` with an earlier `completedAt` sets `activeSeconds = min(activeSeconds, completedAt − startedAt)`: a correction can shorten active time but never add to it.
- `/progress/summary` `xpEarned` and the badges' workout-XP fact both count `session_correction` deltas.
