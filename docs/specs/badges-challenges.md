# Badges & Challenges — task spec (P1, proposal)

Status: **approved** (Vipin, via Tech Lead, 2026-10-21); the §13 decisions are locked. This PR is docs only. Implementation follows the PR split in §12, starting with the contract PR.

## 1. Goals and scope

PRD §4 P1 lists **"Badges and challenges"** (and, separately, "Leaderboards"). PRD principles apply: *rewards use verifiable, clearly defined rules* and *gamification rewards consistency and improvement*.

In scope:
- **Badges**: permanent achievements from fixed, server-evaluated rules over data we already store.
- **Challenges**: *personal* goals, either open-ended or **time-boxed weekly**, created from built-in templates. A user can join, leave, see progress, and complete them.
- Bonus XP for both, through the existing XP ledger.

Out of scope: leaderboards, friends, shared or group challenges, sponsored rewards, redemption (P2), push notifications (FCM is deferred to P1 per plan decision 8), and AI. Everything is rule-based and uses no paid service.

## 2. Inputs (existing data only)

| Source | Field used | Day / time basis | Client can backdate? |
|---|---|---|---|
| `workout_sessions` | `status='completed'`, `completedAt`, `activeSeconds` | local date of `completedAt` | No (server sets `completedAt`) |
| `exercise_set_logs` | `completed=true`, `reps`, `weightKg` | via the session | No (bound to the session) |
| `xp_transactions` | net sum (awards − adjustments) | — | No |
| streak | same rule as `/progress/summary.streakDays`: consecutive local days with ≥ 1 completed session | profile tz | No |
| `food_logs` | row exists with `date` | `date` (client) | **Yes** |
| `water_logs` | `SUM(amountMl)` per `date` | `date` (client) | **Yes** |
| `body_measurements` | row with any value | `date` (client) | **Yes** |

**Local day** means the profile timezone (`user_profiles.timezone`, default `Asia/Kolkata`), with the same helpers as the summary (`localDate`, `startOfLocalDay`). A **week** is ISO Monday 00:00 to Sunday 24:00 in that timezone (decided). The tz is snapshotted on the challenge row at join, so a later tz change doesn't move an open window.

**Water goal** = `user_profiles.dailyWaterTargetMl` (the existing profile field, API `dailyWaterTargetMl`, 250–10 000) when set, otherwise **2000 ml**. Badges use the goal at evaluation time. A weekly challenge snapshots it at join (`waterGoalMl` on the row), so editing the goal mid-week doesn't change that week's target.

## 3. Starter badge catalog

Codes are stable identifiers (UPPER_SNAKE). Titles and descriptions live server-side and are returned by the API, so the app ships no rules. Each badge is earned **at most once per user** and is never revoked (see §9).

| Code | Rule (exact) | Bonus XP |
|---|---|---|
| `FIRST_WORKOUT` | count(completed sessions) ≥ 1 | 10 |
| `WORKOUTS_10` | count(completed sessions) ≥ 10 | 25 |
| `WORKOUTS_50` | count(completed sessions) ≥ 50 | 50 |
| `WORKOUTS_100` | count(completed sessions) ≥ 100 | 100 |
| `STREAK_3` | streak ≥ 3 at evaluation time | 15 |
| `STREAK_7` | streak ≥ 7 | 30 |
| `STREAK_30` | streak ≥ 30 | 100 |
| `SETS_500` | count(set logs, `completed=true`, in completed sessions) ≥ 500 | 50 |
| `HOUR_TOTAL_10` | SUM(`activeSeconds`) of completed sessions ≥ 36 000 | 30 |
| `LONG_SESSION_60` | one completed session with `activeSeconds` ≥ 3 600 | 20 |
| `FIRST_FOOD_LOG` | count(food_logs) ≥ 1 | 5 |
| `FOOD_LOG_7_DAYS` | ≥ 7 distinct `food_logs.date` within any 7 consecutive local dates ending ≤ today | 20 |
| `HYDRATED_7` | 7 distinct dates with SUM(`amountMl`) ≥ the water goal (§2), each date ≤ today | 20 |
| `FIRST_MEASUREMENT` | count(body_measurements) ≥ 1 | 5 |
| `MEASURE_4_WEEKS` | measurements in 4 distinct ISO weeks | 20 |
| `LEVEL_5` | `balanceFor(net XP).level` ≥ 5, computed **excluding badge/challenge bonus XP** | 0 |

Constants live in one `badges.rules.ts` (like `xp.rules.ts`), and the catalog is versioned in code. Adding a badge later is a code change and is backfilled (§6). Changing a rule never revokes an earned badge.

## 4. Challenges

**Types:**
- `weekly`: a window of the current ISO week (Mon–Sun, local). It can be joined any time during the week; progress counts only events inside the window, including events from before joining in that same week.
- `personal`: an open window from join until the target is reached, with an optional deadline of N days.

**Built-in templates** (no user-defined rules in v1):

| Template code | Type | Metric | Target options |
|---|---|---|---|
| `WEEKLY_WORKOUTS` | weekly | completed sessions | 2, 3, 4, 5 |
| `WEEKLY_ACTIVE_MINUTES` | weekly | SUM(activeSeconds)/60 | 60, 90, 150 |
| `WEEKLY_WATER_DAYS` | weekly | dates with water ≥ the water goal | 3, 5, 7 |
| `WEEKLY_FOOD_LOG_DAYS` | weekly | dates with ≥ 1 food log | 3, 5, 7 |
| `PERSONAL_WORKOUTS_N` | personal | completed sessions since join | 10, 20, 30 (deadline 30/60/90 days) |
| `PERSONAL_STREAK_N` | personal | best streak reached since join | 7, 14, 30 |

**Lifecycle** (`status`): `active` → `completed` (target reached inside the window) | `expired` (window ended, target not reached) | `left` (user left).
- **Join**: at most one *active* challenge per template per user. Weekly instances are unique per (user, template, week start). Limit: 5 active challenges in total.
- **Leave**: allowed only while `active`, and gives no XP. Re-joining the same weekly template in the same week is **not** allowed (`409 CHALLENGE_ALREADY_JOINED`), which prevents leave/rejoin churn. A personal template can be re-joined after leaving, as a new row.
- **Progress** = `min(current, target)`. It's computed from source data, not counters, so it's always correct after edits or deletes. For read speed it's cached on the row (`progress`), and recomputed on every relevant write and on read.
- **Completion** is permanent once reached (it isn't revoked if a log is deleted later; see §9). Expiry is applied lazily on read and by the daily job (§6).

## 5. XP interaction

- Bonus XP is awarded **only** through `xp_transactions`, using the same insert-first, unique-key pattern as workouts. New `XpSourceType` values are `badge` and `challenge`; `sourceId` = `user_badges.id` / `user_challenges.id`. The unique key `(userId, sourceType, sourceId)` makes double awards impossible.
- The badge or challenge row and its XP row are inserted in **one transaction**. If the badge insert hits its unique key, no XP is written.
- Workout XP is unchanged. Bonus XP never feeds back into rules except `LEVEL_5`, which excludes bonus XP so there's no cascade.
- Reversals: none in v1 (§9). Ledger `reason` is e.g. `Badge: 7-day streak` / `Challenge: 3 workouts this week`.
- **Weekly cap (decided):** total *challenge* bonus XP ≤ 150 per user per ISO week (Monday start, profile tz, keyed by the week of `completed_at`). An award that would exceed the cap is cut down to the remainder, and may be 0. A 0 award still inserts the ledger row (amount 0), so it can never be paid later. Badge XP is not capped.

## 6. Awarding: server-side only, idempotent

- Clients never claim a badge or completion. There are no write endpoints for badges, and challenge writes are limited to join and leave.
- **Event-driven at write time:** after the source write commits (session complete, set upsert, food/water/measurement create, update or delete), call `achievements.evaluate(userId, kinds)` with the affected rule families. v1 runs it in-process after the commit. It's best-effort; a failure is logged with `warn` and never fails the user's write.
- **Insert-first**: `INSERT ... ON CONFLICT DO NOTHING` on `user_badges (user_id, badge_code)`. Only if a row was inserted, insert the XP row in the same tx. Retried requests, concurrent devices and a re-run job all converge.
- **Backfill/repair job**: an idempotent `evaluateAll(userId)` that runs the same rules. It's triggered (a) once per user by a one-off script after the deploy that introduces a badge (no shell on Render Free, so run locally like `db:seed`), (b) lazily when `GET /badges` sees `user_profiles.badges_evaluated_version < CATALOG_VERSION`, and (c) for challenge expiry, on read. No cron is needed on the free tier.
- **Time rule**: "today" is the profile-tz local date at evaluation. Rules that use client `date` only count dates ≤ today (local) **and** rows with `createdAt` ≥ `startOfLocalDay(date) − 1 day` (see §9).

## 7. Proposed data model

```
badge_definitions are in code (not a table): code, title, description, family, bonusXp.

user_badges
  id uuid pk
  user_id uuid fk -> users.id ON DELETE CASCADE
  badge_code varchar(40)           -- must be a known code (checked in app)
  earned_at timestamptz(3) default now()
  catalog_version int
  UNIQUE (user_id, badge_code)
  INDEX (user_id, earned_at)

user_challenges
  id uuid pk
  user_id uuid fk -> users.id ON DELETE CASCADE
  template_code varchar(40)
  target int CHECK (target > 0)
  window_start date, window_end date  -- local dates, inclusive; personal: end = start + deadline - 1 (null = none)
  timezone varchar(64)                -- snapshot at join
  water_goal_ml int?                  -- snapshot at join (water templates only)
  status enum(active, completed, expired, left)
  progress int default 0
  joined_at, completed_at?, left_at?, updated_at timestamptz(3)
  UNIQUE (user_id, template_code, window_start)          -- one weekly instance per week
  partial UNIQUE (user_id, template_code) WHERE status='active'
  INDEX (user_id, status)

user_profiles
  + badges_evaluated_version int default 0

XpSourceType enum + badge, challenge
```

- **Delete cascade:** both tables cascade from `users` (account deletion), like every other user table. XP rows already cascade.
- **Export**: `/me/export` gains `badges[]` (code, earnedAt) and `challenges[]` (all fields). The export test is extended.

## 8. API sketch (OpenAPI-style excerpt; not yet in `openapi.yaml`)

```yaml
paths:
  /badges:
    get:   # catalog + earned state; triggers lazy backfill (§6)
      responses:
        '200': { content: { application/json: { schema: { $ref: BadgeList } } } }
  /challenges/templates:
    get:   # built-in templates with allowed targets
      responses: { '200': { schema: { type: array, items: { $ref: ChallengeTemplate } } } }
  /challenges:
    get:   # the user's challenges, newest first
      parameters: [status?: active|completed|expired|left, cursor?, limit? (1..100, default 20)]
      responses: { '200': { schema: { $ref: ChallengePage } } }   # { items, page: PageMeta } like /xp/ledger
    post:  # join
      parameters: [Idempotency-Key (required, as other creates)]
      requestBody: { templateCode: string, target: integer }
      responses:
        '201': ChallengeView
        '400': VALIDATION_FAILED          # unknown template or target not allowed
        '409': CHALLENGE_ALREADY_JOINED   # active one exists, or this week's instance existed
               | CHALLENGE_LIMIT_REACHED  # 5 active
               | IDEMPOTENCY_KEY_REUSED | IDEMPOTENCY_IN_PROGRESS
  /challenges/{challengeId}:
    get:  { responses: { '200': ChallengeView, '404': NOT_FOUND } }   # other users' ids are 404
  /challenges/{challengeId}/leave:
    post:
      parameters: [Idempotency-Key (optional; replay as elsewhere)]
      responses:
        '200': ChallengeView              # status=left; leaving an already-left one is 200 (no-op)
        '409': CHALLENGE_NOT_ACTIVE       # completed or expired
        '404': NOT_FOUND
components:
  schemas:
    Badge:      { code, title, description, family: workout|streak|nutrition|hydration|measurement|level,
                  bonusXp: integer, earnedAt: date-time|null, progress: { current: int, target: int }|null }
    BadgeList:  { items: Badge[], earnedCount: int }
    ChallengeTemplate: { code, type: weekly|personal, title, metric, unit, targets: int[], deadlineDays: int|null }
    ChallengeView: { id, templateCode, type, title, target, progress, unit,
                     windowStart: date, windowEnd: date|null, timezone, waterGoalMl: int|null, status,
                     joinedAt, completedAt|null, leftAt|null, bonusXp, xpAwarded: int }
```

Responses that complete something are side effects of other writes, so existing responses (e.g. session complete) **may** gain an optional `newBadges: Badge[]` in a later, additive contract PR. v1 apps poll `GET /badges` after a completion.

## 9. Anti-abuse

- **Backdating** (food, water and measurement `date` is client-supplied): rows count only if `date ≤ today` and the row was created on that local day or the next (`createdAt < startOfLocalDay(date + 2)`, decided). Late entries still show in the diary but don't earn badges or challenge progress. Workout rules use server timestamps only.
- **Delete and re-log**: badges and completed challenges are never revoked, and the unique keys make a second award impossible. Progress on *active* challenges is recomputed from source data, so deleting logs lowers it.
- **Discarded sessions** (correction path) no longer count for active progress. Already-earned items stay, and the XP rule is unchanged.
- **Water spam**: only daily sums ≥ the water goal count; the existing per-entry validation bounds still apply. Optionally cap a day's countable water at 10 L.
- **Leave/rejoin churn**: same-week rejoin is blocked; at most 5 active.
- **Rate limits**: the existing per-user throttler applies to the join and leave writes.

## 10. Android UX notes

- Profile/Home: a "Badges" row with an earned count opens a grid. Locked badges are greyed out with a progress bar (`progress.current/target`); earned ones show their date.
- Workout-complete screen: after `complete`, refetch `GET /badges` and show a one-off "Badge earned" sheet for codes newly earned since the last cached list (cached in Room).
- Challenges tab section: "This week" templates with target chips, then join, which sends an Idempotency-Key per tap. Active cards show progress, days left (from `windowEnd` in the challenge tz) and Leave behind a confirm dialog. Completed and expired cards appear in history.
- Errors are branched on `code` (`CHALLENGE_ALREADY_JOINED`, `CHALLENGE_LIMIT_REACHED`, `CHALLENGE_NOT_ACTIVE`).
- Offline: read-only cached lists; join and leave need the network (no offline queue in v1).
- Copy says "Bonus XP" and never implies money or rewards (P2).

## 11. Test plan

- **Unit (no DB):** every rule against fixtures at its boundary (n−1 / n); week windows across IST midnight and Sun→Mon; the tz snapshot; the backdating cut-off; that `LEVEL_5` excludes bonus XP.
- **Integration (DB_TESTS):**
  - each family is awarded once, even with concurrent evaluate calls (`Promise.all`) → one `user_badges` row and one XP row;
  - a retried session complete doesn't double-award;
  - a backfill run twice gives the same result;
  - join with and without the same Idempotency-Key, different body → 409;
  - same-week rejoin → 409;
  - 6th active → 409;
  - leave, then leave again → 200 no-op;
  - leaving a completed challenge → 409;
  - another user's challenge → 404;
  - deleting a log lowers active progress but not a completed one;
  - discarded session;
  - backdated log ignored;
  - expiry on read;
  - account deletion cascades;
  - export includes both.
- **Contract test:** new paths, codes and required fields are in `openapi.yaml`.
- **QA (T16 owner):** acceptance rows added to `docs/qa/acceptance-checklist.md` by QA.
- **Android:** ViewModel tests for the newly-earned diff; UI tests for join and leave and the error states.

## 12. PR split

1. **Contract**: `openapi.yaml` paths, schemas and error codes from §8, plus the contract test. Android review.
2. **Schema**: migration for the tables, enums, unique and partial-unique indexes, `XpSourceType` values and the profile column; export inclusion with test.
3. **Engine**: `badges.rules.ts`, `challenges.rules.ts`, `AchievementsService.evaluate/evaluateAll`, hooks after the existing writes, the backfill script, and unit and integration tests.
4. **API**: controllers for `/badges` and `/challenges*`, Idempotency-Key, paging, and integration tests.
5. **Android**: badges grid, challenge cards, earned sheet, and tests.

Each PR stays small and additive, and the backend PRs merge in order.

## 13. Decisions (approved by Vipin via Tech Lead, 2026-10-21)

1. **Scope:** P1 badges and challenges are approved. Leaderboards, social, sponsored rewards and redemption stay out.
2. **XP:** badges and challenges grant bonus XP as in §3 and §4. Challenge bonus XP is capped at **150 per user per ISO week** (§5).
3. **Backdating:** a client-dated food, water or measurement entry counts if it was created **on the same local day or the next one** (`createdAt < startOfLocalDay(date + 2)`), with `date ≤ today` (§9).
4. **Revocation:** **never.** Earned badges and completed challenges stay, even if the underlying data is deleted or a session is discarded.
5. **Backfill:** **at release**, for existing users, via the lazy check on `GET /badges` plus the locally run script (§6). Existing history counts, subject to the backdating rule.
6. **Water goal:** the profile's `dailyWaterTargetMl` if set, else 2000 ml (§2).
7. **Week:** starts **Monday** in the profile timezone (default `Asia/Kolkata`).

## 14. Risks

- **Write latency**: evaluation adds queries to hot writes. Mitigate by evaluating only the affected families, using indexed counts, and running after commit.
- **Render Free has no cron or shell**: backfill and expiry are lazy on read plus a locally run script. A missed evaluation self-heals on the next read.
- **Timezone edge cases** (tz change mid-week, IST midnight): handled by the tz snapshot plus unit tests at the boundaries.
- **Rule creep and inconsistent copy between app and server**: titles and rules are served by the API only.
- **XP inflation** from bonuses: handled by the caps and by excluding bonus XP from `LEVEL_5`.
- **Supabase Free row/size limits**: tiny (≤ ~20 badge rows and ~52 weekly challenge rows per user per year).
