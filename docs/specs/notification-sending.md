# Notification sending (contract only)

**Status:** spec and `contracts/openapi.yaml` only. No code, no migration, no workflow in this PR. Builds on [notification-preferences.md](notification-preferences.md) (#116), which stores the per-type flags, the master switch and quiet hours, and ships the pure helper `isQuietAt` in `notification-prefs.rules.ts`.

**Free only.** Delivery is Firebase Cloud Messaging (FCM) **HTTP v1**, which has no per-message charge. The scheduler is woken by a free mechanism (see [Wake mechanism](#wake-mechanism)). No paid service, queue, or Render add-on. Anything that would cost money needs Vipin.

**Kill switch:** nothing is sent unless `NOTIFICATIONS_SENDING_ENABLED=true`, and it **stays off until Vipin says to turn it on**. Device-token endpoints and reminder settings work regardless, so the Android app can ship registration first.

## 1. Types and triggers
The seven existing preference types. Two families:

- **Scheduled reminders** fire at local wall-clock *slots* computed from the user's reminder settings (§3), and only if their condition is still true when the slot is processed.
- **Event notifications** are created by a server-side event, written to an outbox in the same transaction as the event.

| type | family | trigger | condition checked at send time | slot key |
|---|---|---|---|---|
| `workout_reminder` | reminder | `workoutReminder.time` on each local weekday in `workoutReminder.days` | user has an **active, non-paused** plan, and no workout session started on that local date | `YYYY-MM-DD` |
| `streak_reminder` | reminder | `streakReminder.time` daily | current streak (as in `GET /progress/summary`) is ≥ 1 and would break if nothing is logged today | `YYYY-MM-DD` |
| `water_reminder` | reminder | every `waterReminder.intervalMinutes` from `waterReminder.start` up to (not after) `waterReminder.end` | water logged today < that day's target (dated target from target history; else 2000 ml, as the badge rule) | `YYYY-MM-DDTHH:MM` |
| `food_log_reminder` | reminder | each time in `foodLogReminder.times` | no food log for that local date created since the previous food slot of the day (or since 00:00 for the first) | `YYYY-MM-DDTHH:MM` |
| `challenge_updates` | event | joined challenge: **deadline near** (24 h before `windowEnd`, not yet completed), **completed**, **expired** | challenge still in that state | `<challengeId>:<event>` |
| `badge_earned` | event | badge awarded by a **live event after release** (see below) | – | `<badgeCode>` |
| `progression_suggestion` | event | a completed session produces a suggestion for an exercise whose suggested target differs from the last one notified | suggestion still pending (not accepted) | `<planId>:<exerciseId>:<suggestedKgOrReps>` |

- **Only live events notify. Backfills never do.** An outbox row is created only when a badge award, challenge transition or progression suggestion is **caused by a live user write after release**: a workout session completed, or a food, water or body log written, through the API. Evaluation code takes an explicit `cause` (`live` | `backfill`) and writes outbox rows only for `live`. None of these create outbox rows:
  - `npm run badges:backfill`, `targets:backfill` and any future backfill script;
  - the **first evaluation of a user's existing history**, including the lazy release backfill on `GET /badges` (`AchievementsService.ensureBackfilled`, once per catalog version) and the re-evaluation after a catalog version bump;
  - **challenges:** transitions found during a backfill or lazy evaluation on read. Lazy expiry on `GET /challenges` is evaluated as `backfill`, so "expired" is sent only when a live write or the tick detects it after release. "Deadline near" comes from the tick, which is a live check, and is sent once per challenge (slot key);
  - **progression:** suggestions are computed on read (`GET …/progression-suggestions`), and reading never notifies. Only the session-completion hook after release does, and only for a suggestion that differs from the last notified one.
  - Scheduled reminders aren't affected; they come from the tick.
- **Generic copy only** (decision 7): title and body come from a fixed per-type template with no user data: no health numbers (weights, reps, kcal, macros, water), no food names, no body weight, no badge or challenge names that imply them. Notifications show on lock screens. Example: "Time for a glass of water", "You earned a new badge". Details live behind the deep link (`data.route`, e.g. `challenges/<id>`), which the app opens after unlock. `data` carries only ids and the route.

## 2. Gating: master switch, type flag, quiet hours
At **send time** (never at schedule time), in this order:
1. `NOTIFICATIONS_SENDING_ENABLED` is true;
2. preferences `enabled` (master) is true **and** the type flag is true (effective, defaults merged);
3. the user has ≥ 1 registered device;
4. the per-type and overall daily caps (§4) are not reached;
5. `isQuietAt(quietHours, now, profile.timezone)` is false, using the **current** profile timezone (nothing snapshotted). **Nothing bypasses quiet hours** (preferences decision 4).

**Drop vs hold (decision 1):**
- **Reminders are dropped** when they land in quiet hours. A 22:30 water reminder is useless at 07:00, and the next slot covers it. The schedule should simply avoid quiet hours: the app warns when a reminder time falls inside them.
- **Events are held** until quiet hours end, then sent on the first tick after `end` (subject to the catch-up limits in §6). Holding repeats are **coalesced**: at most one held notification per type per user is delivered at the end of quiet hours (e.g. "You earned 3 badges"), and the rest are marked `coalesced`.
- Anything held longer than **24 h** (from its `due_at`) is discarded as `expired`, never sent.
- If the master switch or type flag turns off while a notification is held, it is `suppressed` at send time.

## 3. Reminder settings (deferred from preferences decision 3)
A new optional `reminders` object on `NotificationPreferences` (GET) and `NotificationPreferencesUpdate` (PATCH), with the same merge rules (per field; nothing saved on any 400). Stored in a new jsonb column `reminders` on `notification_preferences` (default `{}`; no row still means defaults). The flags still decide whether a reminder is on; these only decide when.

| field | type | default | validation |
|---|---|---|---|
| `workoutReminder.time` | `HH:MM` | **18:00** | `LocalTime` |
| `workoutReminder.days` | array of `mon`…`sun` | **all seven** | unique, 1–7 items; stored in week order |
| `streakReminder.time` | `HH:MM` | **20:00** | `LocalTime` |
| `waterReminder.intervalMinutes` | integer | **120** | 60–240, multiple of 30 |
| `waterReminder.start` / `.end` | `HH:MM` | **09:00 / 21:00** | `start < end` (no crossing midnight) after the merge; error on `reminders.waterReminder` |
| `foodLogReminder.times` | array of `HH:MM` | **["13:30", "20:30"]** | 1–3 unique items, stored sorted |

- Slots on a day: workout and streak one each; water `start, start+interval, …` while `≤ end` (default 7 slots); food one per time.
- **Workout days are weekdays the user picks** (decision 3), all seven by default, with **no coupling to plans**: nothing is derived from or changed by plan days-per-week. The only plan check is the send-time condition (an active, non-paused plan exists).
- Reading `reminders` merges stored values over the defaults, like `types`. `reminders` lives **inside the preferences resource** (decision 5): one `PATCH /me/notification-preferences` changes flags, quiet hours and reminders together, under the same per-user advisory lock and transaction, so concurrent PATCHes to different parts all persist. No separate resource. Defaults are **Needs Vipin** (they are placeholders until he confirms).

## 4. Daily caps
Per user, per local date (profile timezone at send time):

| type | cap/day |
|---|---|
| `workout_reminder` | 1 |
| `streak_reminder` | 1 |
| `water_reminder` | 8 |
| `food_log_reminder` | 3 |
| `challenge_updates` | 3 |
| `badge_earned` | 3 (rest coalesced into the last) |
| `progression_suggestion` | 1 |
| **all types together** | **12** |

- Counted from `notification_sends` rows with status `sent` for that user and local date. A capped notification is marked `capped`, not retried.
- `streak_reminder` is skipped if a `workout_reminder` was sent in the last 2 hours (same nudge).
- Caps are constants in code (a later contract change can make them configurable); not env vars.

## 5. Timezone and DST
- Slots are **local wall-clock times in the current profile timezone**. A zone change applies from the next tick; already-sent slots for "today" in the old zone are not re-sent, because the slot key is the local date and time **and** uniqueness covers it (if the date string is the same, it's the same slot).
- **Spring forward (skipped time):** a slot whose wall time does not exist that day (e.g. 02:30 in New York) fires at the first instant after the gap (03:00 local). It's still the same slot key.
- **Fall back (repeated hour):** a slot in the repeated hour fires **once**, at its first occurrence; the unique slot key stops the second.
- Quiet hours use `isQuietAt`, which already follows these wall-clock rules.
- `Asia/Kolkata` (the default) has no DST; tests use `America/New_York`.

## 6. Scheduler
### Data
- `notification_sends`: `id`, `user_id` (FK → users `ON DELETE CASCADE`), `type`, `slot_key` (text), `send_key` = **unique** `(user_id, type, slot_key)`, `status` (`pending` | `held` | `sending` | `sent` | `dropped_quiet` | `capped` | `suppressed` | `coalesced` | `expired` | `failed` | `skipped_stale`), `due_at` timestamptz, `attempts` int, `claimed_at` timestamptz null, `last_error` (FCM error code only, never the token), `created_at`, `sent_at`. Index `(status, due_at)`.
- Event producers insert `pending` rows (`ON CONFLICT DO NOTHING`) in the event's own transaction: the outbox pattern, so a crash can't lose or duplicate an event.
- Reminder rows are materialized by the tick for slots whose `due_at` has passed (`ON CONFLICT DO NOTHING`).
- Rows older than **30 days** are purged by the tick.

### Delivery semantics: at-most-once per send key
- The tick **claims** due rows with `UPDATE … SET status='sending', attempts=attempts+1 … WHERE status IN ('pending','held') AND due_at <= now() … FOR UPDATE SKIP LOCKED`, so overlapping ticks (two wake-ups, a cold start during a tick) never pick the same row.
- The row is marked `sent` after FCM accepts it for at least one device.
- **Why at-most-once** (decision 2): a duplicate reminder is worse for users than a missed one, and FCM has no idempotency key.
- **Stuck rows:** a row in `sending` whose claim is older than **10 minutes** (`claimed_at`; a tick is capped at 25 s, so only a crashed or killed process leaves one) is marked `failed` with `last_error = 'STUCK_SENDING'` by the next tick. It is **never retried**, and each one is logged at `warn` with the send id, type and age (no token, no user data).
- **Retries** happen only within one tick, for FCM `UNAVAILABLE`/`INTERNAL`/429: up to 2 retries with backoff (honouring `Retry-After`, max 10 s total). After that, `failed`. No cross-tick retry.

### Catch-up after downtime (decision 8)
Render Free sleeps, and cron can be late, so a slot may be processed late. Rules:
- **Reminders:** sent only if processed within **30 min** of `due_at` (water: within the interval, max 30 min). Older slots are `skipped_stale`; **only the latest missed slot per type** is considered, so a long outage never produces a burst.
- **Events:** sent if within **24 h**; older are `expired`.
- **Look-back:** the tick materializes reminder slots for at most the last **2 h**; anything older simply never exists.
- The send-time checks (§2) and conditions (§1) are re-evaluated when processed, so a late water reminder isn't sent if the user has since met the target.

### Wake mechanism
An in-process timer alone **does not work** on Render Free: the instance spins down after ~15 min without HTTP traffic, and a timer does not count as traffic, so overnight and quiet periods would never tick.

**Recommended (Tech Lead's free default; still pending Vipin):** a GitHub Actions `schedule` workflow (free: the repo is public, and standard GitHub-hosted runners have no minute charge for public repos) runs every **15 minutes** and calls `POST /api/v1/internal/notifications/tick` with header `Authorization: Bearer <NOTIFICATIONS_CRON_SECRET>`.
- Each call wakes the instance (~50 s cold start), runs one bounded tick (max `NOTIFICATIONS_TICK_MAX_ROWS` rows, max 25 s), and returns counts `{materialized, sent, dropped, capped, failed}`. The workflow prints the counts only.
- GitHub cron is best-effort (often 5–15 min late, occasionally skipped); the catch-up rules above absorb that, which is why reminder grace is 30 min.
- Cost: ~3,000 short runs/month, free while the repo stays **public**. If it is ever made private, the 2,000 free minutes/month would not cover a 15-min cron. **Needs Vipin.**
- GitHub disables scheduled workflows after 60 days without repo activity; a disabled cron means no reminders (events still go out when the app wakes the instance).
- Side effect: pinging every 15 min keeps the instance mostly awake, which reverses the DEPLOY.md "no keep-alive pinger" choice. Render Free allows 750 instance-hours/month for the workspace, enough for one always-on service but not two. **Needs Vipin.**

**Alternatives** (for Vipin's choice):
- **Free external cron** (e.g. cron-job.org) calling the same endpoint every 15 min: no Actions minutes, same keep-awake effect; another account to manage.
- **In-process timer + opportunistic ticks only:** a `setInterval` (every 5 min) while awake, plus a tick on startup. No external caller, so no sends while asleep; reminders mostly fire only when the user's app has recently made requests. Cheapest, least reliable.
- The in-process timer runs **in addition** to any external wake (safe because claims use `SKIP LOCKED` and send keys are unique), and on boot after migrations.

### Internal tick endpoint (not in the client contract)
`POST /api/v1/internal/notifications/tick`
- **Not in `contracts/openapi.yaml`** (decision 6): it is not a client API, and the contract requires user bearer auth everywhere. Excluded from Swagger.
- `backend/test/contract.spec.ts` has an explicit **`x-internal` allowlist** (`INTERNAL_ROUTES = ['post /internal/notifications/tick']`). A test asserts that openapi has no `/internal` path and that every allowlisted route is internal, and the list is exported so a route-vs-openapi drift test exempts **only** these routes; every other registered route must still match openapi. Adding an internal route means adding it to that list in review.
- Auth: `Authorization: Bearer <NOTIFICATIONS_CRON_SECRET>`, constant-time compare; 404 when the secret env var is unset (endpoint off); 401 on mismatch. Per-IP rate limit 6/min.
- Body empty; idempotent by construction. Returns `200 {counts}`, or `200 {skipped: "sending disabled"}` when the kill switch is off (still purges and expires).

## 7. Delivery: FCM HTTP v1
- `POST https://fcm.googleapis.com/v1/projects/{FCM_PROJECT_ID}/messages:send`, one request per device token (v1 has no multicast). OAuth2 access token from the service account (`https://www.googleapis.com/auth/firebase.messaging`), cached until 5 min before expiry.
- Message: `notification {title, body}`, `data {type, route, sendId}`, `android {priority: "normal" for reminders, "high" for events; ttl: "1800s" reminders, "86400s" events; collapse_key: type}`.
- The Android app does not need Firebase Auth; only the FCM SDK.

### Error handling
| FCM response | action |
|---|---|
| 200 | device `last_sent_at` updated |
| 404 `UNREGISTERED` | **delete that device row** |
| 400 `INVALID_ARGUMENT` naming the token, or `SENDER_ID_MISMATCH` (403) | **delete that device row** |
| 429 `QUOTA_EXCEEDED`, 503 `UNAVAILABLE`, 500 `INTERNAL` | retry within the tick (§6), then `failed` |
| 401/403 auth errors | stop the tick, log `FCM_AUTH` (no secret in logs); rows stay `pending` for the next tick |

## 8. Device tokens (client API)
Table `notification_devices`: `id` uuid, `user_id` (FK → users `ON DELETE CASCADE`), `token` text **unique** (globally), `platform` (`android`), `app_version` text null, `refresh_family_id` uuid null (the session's refresh-token family, from the access token's `sid` claim; see Session binding), `created_at`, `last_seen_at`, `last_sent_at`.

`POST /me/notification-devices` body `{token, platform, appVersion?}` → `200 NotificationDevice` (`id, platform, appVersion, createdAt, lastSeenAt`; the token is **never returned**).
- **Upsert by token:** same user → updates `last_seen_at`/`app_version`; another user (shared phone, re-login) → the row moves to the caller. So it's idempotent even without a key.
- `Idempotency-Key` optional (usual replay/409 rules). Max **10 devices** per user; registering an 11th deletes the least recently seen.
- Clients call it on every app start and on FCM `onNewToken`.
- Validation (`400 VALIDATION_FAILED`; error `details.fields` never echo the token value): `token` string 1–4096 chars, no whitespace; `platform` enum `[android]`; `appVersion` ≤ 32 chars; unknown properties rejected.

**Idempotency storage never holds the token.** Checked against `backend/src/idempotency/idempotency.service.ts` and the `IdempotencyKey` model:
- The request side already stores only `request_hash` = SHA-256 of the canonical JSON body, plus method, path and scope. **The raw request body is never persisted**, so the token appears there only as part of a one-way hash.
- The response side stores `response_body` for replay, unless the path is token-bearing (`isTokenBearingPath`) or the scope is anonymous. For this route the stored response is the `NotificationDevice`, which **has no token field**. So no change to the idempotency service is needed; the implementation keeps the response DTO token-free, and a test asserts it.
- Error responses (`400`) are stored the same way, which is why validation messages must not echo the token.
- Request logging must not log this body (the logger already logs no bodies; a test checks the token string is absent from logs).

`DELETE /me/notification-devices/{deviceId}` → `204`, also when it doesn't exist or isn't the caller's (no leak, idempotent). `Idempotency-Key` optional.

Both: 401, 409 (key reuse), `429 RATE_LIMITED` per user per route, `NOTIFICATION_DEVICES_RATE_LIMIT_PER_MINUTE` (default 30).

### Session binding (decision 4; checked against the auth code)
**Finding:** the access token does **not** carry the refresh-family id today. `TokenService.signAccess(userId)` signs only `{sub, typ: 'access'}` plus `iss`, `aud`, `exp` and a random `jti`; `verifyAccess` returns only the user id. Families exist only on `refresh_tokens.family_id` (a UUID per login, kept across rotations). `POST /auth/logout` takes the refresh token in the body and revokes the presented token's **whole family** (`TokenService.revoke`, since backend/logout-revoke-family; docs/specs/auth-logout.md); reuse detection revokes the whole family (`revokeFamily`).

**How devices are bound:**
1. Access tokens gain a session claim `sid` = the refresh `family_id`. `issue()` passes the new family id; `rotate()` passes `row.familyId`. The guard exposes it to handlers (e.g. `@CurrentSession()`); it isn't needed elsewhere. No contract change (tokens are opaque to the client).
2. `POST /me/notification-devices` stores `refresh_family_id = sid` on the device row (updating it on every upsert, so a re-login on the same phone rebinds the token to the new session).
3. **Logout:** `AuthService.logout` looks up the presented refresh token's `family_id` (owner-checked, as now) and, in the same transaction as the revoke, deletes `notification_devices` with that `user_id` and `refresh_family_id`. A logout with an unknown or already-revoked token still deletes nothing else.
4. **Reuse detection / family revoke:** `revokeFamily` deletes that family's devices too.
5. **Transition:** access tokens issued before this ships have no `sid` (they live ≤ 15 min). A POST with such a token stores `refresh_family_id = null`; the next POST after a refresh fills it. Null-family rows are still cleaned by FCM errors, the 60-day stale rule and account deletion.
6. Access tokens stay valid until expiry after logout (unchanged), but the device row is already gone, so nothing is sent to that phone.

### Cleanup
- **Sign-out:** as above, by refresh family. The app should also call DELETE before logout (best effort).
- **Account deletion:** cascade; also nothing is sent to a user being deleted (send-time join on users).
- **FCM `UNREGISTERED`/invalid:** row deleted (§7).
- **Stale:** `last_seen_at` older than **60 days** → deleted by the tick (FCM tokens can go stale after ~1 month idle; the app refreshes on start).

## 9. Export and deletion
- `GET /me/export` adds `notificationDevices` (`NotificationDevice[]`, **no tokens**) and `notificationSends` (last 30 days: `type`, `status`, `dueAt`, `sentAt`; no slot internals or errors), and `notificationPreferences` gains `reminders`. They are optional in `DataExport` until implemented, then required.
- `DELETE /me` removes devices and sends by cascade. Nothing is held in FCM (it stores no per-user data from us beyond the in-flight message).

## 10. Configuration (env only)
All secrets live in Render env vars (and the GitHub secret for the cron), never in the repo. Listed in [DEPLOY.md](../DEPLOY.md):
- `NOTIFICATIONS_SENDING_ENABLED` (default `false`)
- `FCM_PROJECT_ID`
- `FCM_SERVICE_ACCOUNT_JSON`: the service-account key JSON (one line). Only `client_email` and `private_key` are used. Never logged.
- `NOTIFICATIONS_CRON_SECRET`: `openssl rand -base64 48`; also a GitHub Actions secret of the same name, plus `FITNESS_API_BASE_URL`, if the Actions wake is chosen.
- `NOTIFICATIONS_TICK_MAX_ROWS` (default 500)
- `NOTIFICATION_DEVICES_RATE_LIMIT_PER_MINUTE` (default 30)

## 11. Tests (for the implementation PRs)
- Slot generation: each type with defaults and custom settings; water interval boundaries (`end` included only if on-grid); food times sorted; workout weekdays.
- DST in `America/New_York`: skipped 02:30 slot fires at 03:00 once; repeated 01:30 slot fires once.
- Gating order: kill switch, master off, type off, no devices, caps (per type and 12 total), quiet: reminder dropped, event held then sent after `end`, coalescing, 24 h hold expiry, prefs turned off while held → suppressed.
- Idempotency: two concurrent ticks send each row once (`SKIP LOCKED`); duplicate event insert is a no-op; stuck `sending` → `failed` and never resent.
- Catch-up: a 3 h outage sends at most one (latest) reminder per type and only within grace; events within 24 h.
- FCM: mocked HTTP; `UNREGISTERED` and invalid token delete the device; 503 retried then `failed`; auth error leaves rows pending; the token never appears in logs or errors.
- Devices API: upsert, move between users, 10-device eviction, DELETE of someone else's id is 204 and changes nothing, validation, 409, 429; logout and refresh-reuse delete the family's devices; account deletion cascades; export has no tokens.
- **Live-only outbox:** after release, a session completion that earns a badge creates exactly one `badge_earned` row. `npm run badges:backfill`, the lazy backfill on `GET /badges` for a user with existing qualifying history, and a catalog version bump create **none**. A challenge completed during lazy or backfill evaluation creates none; one completed by a live log does. Lazy expiry on `GET /challenges` creates none. Reading progression suggestions creates none; a session completion with a new suggestion creates one. `targets:backfill` creates none.
- **Token never stored:** after `POST /me/notification-devices` with an `Idempotency-Key`, the `idempotency_keys` row (`request_hash`, `response_body`) and the logs don't contain the token string; neither does a 400 response for an invalid token.
- Tick endpoint: 404 when unset, 401 on a wrong secret, counts returned.

## Proposed PR split
1. Reminder settings (`reminders` on preferences) + device endpoints + cleanup (no sending).
2. Outbox, scheduler, tick endpoint, FCM client behind the kill switch (mocked in CI).
3. The wake workflow (only after Vipin chooses), then flip `NOTIFICATIONS_SENDING_ENABLED`.

## Needs Vipin
1. **Firebase project + service account:** create (or reuse) a free Spark-plan Firebase project, add the Android app (package name, `google-services.json` for Android), create a service account with the *Firebase Cloud Messaging API Admin* role, and put its key JSON in Render as `FCM_SERVICE_ACCOUNT_JSON` with `FCM_PROJECT_ID`. Only he can create accounts and keys.
2. **Wake mechanism:** GitHub Actions cron (free while the repo is public; Tech Lead's recommended default), a free external cron service, or in-process only (unreliable). Any external pinger also **reverses the "no keep-alive pinger" choice** and uses most of Render Free's 750 instance-hours.
3. **Default reminder times:** workout 18:00 all days, streak 20:00, water every 120 min 09:00–21:00, food 13:30 and 20:30.
4. **Daily caps** (§4), especially water 8/day and 12 total.
5. **When to turn sending on** (`NOTIFICATIONS_SENDING_ENABLED`; it stays off until he says so), and Android's runtime permission prompt (Android 13+ `POST_NOTIFICATIONS`) wording and timing.

## Decisions (Tech Lead, #119)
1. **Quiet hours:** reminders that fall in quiet hours are dropped. Events are held and sent merged per type when quiet hours end; anything older than 24 h is discarded.
2. **At-most-once:** a row stuck in `sending` for 10 minutes is marked `failed` (`STUCK_SENDING`), logged, and never retried.
3. **Workout days** are weekdays the user picks, all 7 by default, with no coupling to plans.
4. **Session binding:** the access token has no family id today, so access tokens gain a `sid` claim (the refresh family). Devices are registered against it at POST time and deleted by it on logout and family revoke.
5. **`reminders` stays inside the preferences resource**: one PATCH, one lock.
6. **The tick endpoint stays out of openapi**, with an explicit `x-internal` allowlist in the contract test (test-only), so drift is still caught for public routes.
7. **Generic copy only:** no health numbers, food or weight in notification text, because of lock screens.
8. **Catch-up:** 30 min for reminders, 24 h for events.
9. **Wake mechanism:** Tech Lead recommends the GitHub Actions cron as the free default. It's still pending Vipin.
10. **Sending stays off** (`NOTIFICATIONS_SENDING_ENABLED=false`) until Vipin says to turn it on.

## Open questions for Tech Lead
None open.
