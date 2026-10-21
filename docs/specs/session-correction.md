# Session correction (PRD 5.3)

PRD 5.3: "Users can correct accidental logs; associated XP and challenge totals must also be updated." This spec records the decision (Tech Lead and Android Developer review, 2026-10-21). The contract is in `contracts/openapi.yaml`.

## Endpoints

Corrections live on their own paths. Each one needs an Idempotency-Key, works on completed sessions only, and always returns `200 SessionCorrectionResult {session, xpDelta, xpBalance, newBadges?}`.

| Call | Use |
| --- | --- |
| `PUT /workout-sessions/{id}/corrections/sets/{clientSetId}` | Upsert by `clientSetId`: edits the existing set in place, or adds one if none has that id. Never creates a duplicate. Body is `SetLogUpsert` (its `clientSetId` must match the path). |
| `DELETE /workout-sessions/{id}/corrections/sets/{clientSetId}` | Delete a set. |
| `PATCH /workout-sessions/{id}/completion` | Fix `completedAt`, `perceivedDifficulty` and `notes`. `completedAt` must be today or yesterday (profile timezone), not in the future, and after `startedAt`. |
| `DELETE /workout-sessions/{id}` | Unchanged: discard a session, completed or not. Still `204` with no body. |

`POST /workout-sessions/{id}/sets` is unchanged and still returns `409 SESSION_COMPLETED` on completed sessions.

## Errors

- `409 SESSION_NOT_COMPLETED`: a correction call on a session that isn't completed.
- `409 CORRECTION_WINDOW_CLOSED`: the session's `completedAt` local date (profile timezone) is before yesterday. Applies to the corrections paths and to `PATCH /completion`, not to discard.
- `422 SESSION_EMPTY`: the correction would leave no completed set. The client offers discard (`DELETE /workout-sessions/{id}`) instead.
- `404 SET_NOT_FOUND`: delete of a set that isn't in the session, e.g. a delete repeated with a new key. The outbox can drop it. An unknown session stays `404 NOT_FOUND`.
- Same Idempotency-Key: the original status and body are replayed.

## Decisions

1. **XP:** the server recomputes `workoutXp(completedSets, activeSeconds)` and writes the difference from the session's current net XP as one `adjustment` ledger row whose `sourceId` is the original award (±, no row when the difference is 0). A session's net XP never goes below 0. The client never writes XP. Corrections run in one transaction under the session row lock.
2. **Discard:** writes one negative adjustment equal to the session's current net XP, so the net becomes 0. The award row stays, so the session can never be awarded again.
3. **Never revoked:** earned badges and completed challenges, with their bonus XP, stay (badges spec §9).
4. **Active challenges:** progress is recomputed from source data, so it can go down or up.
5. **Re-evaluation:** every correction triggers badge and challenge evaluation. Badges newly earned through it come back in `newBadges`, which uses the same `Badge` schema as `GET /badges`.
