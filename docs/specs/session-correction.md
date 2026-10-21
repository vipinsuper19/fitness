# Session correction (PRD 5.3)

PRD 5.3: "Users can correct accidental logs; associated XP and challenge totals must also be updated." This spec records the decision (agreed by Tech Lead, 2026-10-21). The contract is in `contracts/openapi.yaml`.

## Endpoints

| Call | Use |
| --- | --- |
| `POST /workout-sessions/{id}/sets` | Upsert a set; now also allowed on completed sessions. |
| `DELETE /workout-sessions/{id}/sets/{clientSetId}` | Delete a set, before or after completion. Idempotency-Key required. |
| `PATCH /workout-sessions/{id}/completion` | Fix `completedAt` (today or yesterday, profile timezone), `perceivedDifficulty`, `notes`. Completed sessions only. |
| `DELETE /workout-sessions/{id}` | Unchanged: discard the whole session and reverse its XP. |

On a completed session every correction returns `SessionCorrectionResult {session, xpDelta, xpBalance, newBadges?}`. Removing the last completed set returns `422 SESSION_EMPTY`; the client offers discard instead. Retries with the same Idempotency-Key replay the same status and body.

## Decisions

1. **XP:** the server recomputes `workoutXp(completedSets, activeSeconds)` and writes the difference from the session's current net XP as one `adjustment` ledger row whose `sourceId` is the original award (± amount, zero difference writes no row). The session's net XP is never below 0. The client never writes XP. Corrections run under the session row lock in one transaction.
2. **Never revoked:** earned badges and completed challenges (and their bonus XP) stay, as in the badges spec §9.
3. **Active challenges:** progress on active challenges is recomputed from source data, so a correction can lower or raise it.
4. **Re-evaluation:** every correction triggers badge and challenge evaluation; newly earned badges are returned in `newBadges` when present.
5. **Timestamps:** `completedAt` edits are bounded to today or yesterday so a correction can't move a workout into an older window.
