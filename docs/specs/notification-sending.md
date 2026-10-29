# Notification sending (contract only)

**Status:** spec and `contracts/openapi.yaml` only. No code, no migration, no workflow in this PR. Builds on [notification-preferences.md](notification-preferences.md) (#116), which stores the per-type flags, the master switch and quiet hours, and ships the pure helper `isQuietAt` in `notification-prefs.rules.ts`.

**Free only.** Delivery is Firebase Cloud Messaging (FCM) **HTTP v1**, which has no per-message charge. The scheduler is woken by a free mechanism (see [Wake mechanism](#wake-mechanism)). No paid service, queue, or Render add-on. Anything that would cost money needs Vipin.

**Kill switch:** nothing is sent unless `NOTIFICATIONS_SENDING_ENABLED=true`. Device-token endpoints and reminder settings work regardless, so the Android app can ship registration first.

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
| `badge_earned` | event | badge awarded (live or backfill) | – | `<badgeCode>` |
| `progression_suggestion` | event | a completed session produces a suggestion for an exercise whose suggested target differs from the last one notified | suggestion still pending (not accepted) | `<planId>:<exerciseId>:<suggestedKgOrReps>` |

- **Backfills never notify.** `badges:backfill`, `targets:backfill` and any future backfill write no outbox rows (they would otherwise send historic badges).
- Event payloads are generic text plus a deep link (`data.route`, e.g. `challenges/<id>`). No health data (weights, kcal, water) in the notification body, because it shows on the lock screen.

## 2. Gating: master switch, type flag, quiet hours
At **send time** (never at schedule time), in this order:
1. `NOTIFICATIONS_SENDING_ENABLED` is true;
2. preferences `enabled` (master) is true **and** the type flag is true (effective, defaults merged);
3. the user has ≥ 1 registered device;
4. the per-type and overall daily caps (§4) are not reached;
5. `isQuietAt(quietHours, now, profile.timezone)` is false, using the **current** profile timezone (nothing snapshotted). **Nothing bypasses quiet hours** (preferences decision 4).

**Drop vs hold (decided here):**
- **Reminders are dropped** when they land in quiet hours. A 22:30 water reminder is useless at 07:00, and the next slot covers it. The schedule should simply avoid quiet hours: the app warns when a reminder time falls inside them.
- **Events are held** until quiet hours end, then sent on the first tick after `end` (subject to the catch-up limits in §6). Holding repeats are **coalesced**: at most one held notification per type per user is delivered at the end of quiet hours (e.g. "You earned 3 badges"), and the rest are marked `coalesced`.
- A hold never outlives **24 h**; after that the row is `expired`.
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
- **Workout days are weekdays**, not plan day numbers: plan workouts are an ordered list, not tied to weekdays, so the user picks the days they train. (An Open question below asks whether to derive defaults from the plan's days-per-week.)
- Reading `reminders` merges stored values over the defaults, like `types`. Defaults are **Needs Vipin** (they are placeholders until he confirms).

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
- `notification_sends`: `id`, `user_id` (FK → users `ON DELETE CASCADE`), `type`, `slot_key` (text), `send_key` = **unique** `(user_id, type, slot_key)`, `status` (`pending` | `held` | `sending` | `sent` | `dropped_quiet` | `capped` | `suppressed` | `coalesced` | `expired` | `failed` | `skipped_stale`), `due_at` timestamptz, `attempts` int, `last_error` (FCM error code only, never the token), `created_at`, `sent_at`. Index `(status, due_at)`.
- Event producers insert `pending` rows (`ON CONFLICT DO NOTHING`) in the event's own transaction: the outbox pattern, so a crash can't lose or duplicate an event.
- Reminder rows are materialized by the tick for slots whose `due_at` has passed (`ON CONFLICT DO NOTHING`).
- Rows older than **30 days** are purged by the tick.

### Delivery semantics: at-most-once per send key
- The tick **claims** due rows with `UPDATE … SET status='sending', attempts=attempts+1 … WHERE status IN ('pending','held') AND due_at <= now() … FOR UPDATE SKIP LOCKED`, so overlapping ticks (two wake-ups, a cold start during a tick) never pick the same row.
- The row is marked `sent` after FCM accepts it for at least one device.
- **Why at-most-once:** a duplicate reminder is worse for users than a missed one, and FCM has no idempotency key. A row stuck in `sending` (the process died mid-call) is **not retried**; after 10 minutes the next tick marks it `failed`.
- **Retries** happen only within one tick, for FCM `UNAVAILABLE`/`INTERNAL`/429: up to 2 retries with backoff (honouring `Retry-After`, max 10 s total). After that, `failed`. No cross-tick retry.

### Catch-up after downtime
Render Free sleeps, and cron can be late, so a slot may be processed late. Rules:
- **Reminders:** sent only if processed within **30 min** of `due_at` (water: within the interval, max 30 min). Older slots are `skipped_stale`; **only the latest missed slot per type** is considered, so a long outage never produces a burst.
- **Events:** sent if within **24 h**; older are `expired`.
- **Look-back:** the tick materializes reminder slots for at most the last **2 h**; anything older simply never exists.
- The send-time checks (§2) and conditions (§1) are re-evaluated when processed, so a late water reminder isn't sent if the user has since met the target.

### Wake mechanism
An in-process timer alone **does not work** on Render Free: the instance spins down after ~15 min without HTTP traffic, and a timer does not count as traffic, so overnight and quiet periods would never tick.

**Recommended:** a GitHub Actions `schedule` workflow (free: the repo is public, and standard GitHub-hosted runners have no minute charge for public repos) runs every **15 minutes** and calls `POST /api/v1/internal/notifications/tick` with header `Authorization: Bearer <NOTIFICATIONS_CRON_SECRET>`.
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
- Not in `contracts/openapi.yaml` (it is not a client API, and the contract requires bearer user auth everywhere). Excluded from Swagger.
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
Table `notification_devices`: `id` uuid, `user_id` (FK → users `ON DELETE CASCADE`), `token` text **unique** (globally), `platform` (`android`), `app_version` text null, `refresh_family_id` (the refresh-token family of the session that registered it, from the access token), `created_at`, `last_seen_at`, `last_sent_at`.

`POST /me/notification-devices` body `{token, platform, appVersion?}` → `200 NotificationDevice` (`id, platform, appVersion, createdAt, lastSeenAt`; the token is **never returned**).
- **Upsert by token:** same user → updates `last_seen_at`/`app_version`; another user (shared phone, re-login) → the row moves to the caller. So it's idempotent even without a key.
- `Idempotency-Key` optional (usual replay/409 rules). Max **10 devices** per user; registering an 11th deletes the least recently seen.
- Clients call it on every app start and on FCM `onNewToken`.
- Validation (`400 VALIDATION_FAILED`): `token` string 1–4096 chars, no whitespace; `platform` enum `[android]`; `appVersion` ≤ 32 chars; unknown properties rejected.

`DELETE /me/notification-devices/{deviceId}` → `204`, also when it doesn't exist or isn't the caller's (no leak, idempotent). `Idempotency-Key` optional.

Both: 401, 409 (key reuse), `429 RATE_LIMITED` per user per route, `NOTIFICATION_DEVICES_RATE_LIMIT_PER_MINUTE` (default 30).

### Cleanup
- **Sign-out:** `POST /auth/logout` deletes devices whose `refresh_family_id` is the family being revoked (server-side, so it works even if the client forgets). The app should also call DELETE first. Refresh-token **reuse** detection (family revoked) deletes them too.
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
- Backfills produce no outbox rows.
- Tick endpoint: 404 when unset, 401 on a wrong secret, counts returned.

## Proposed PR split
1. Reminder settings (`reminders` on preferences) + device endpoints + cleanup (no sending).
2. Outbox, scheduler, tick endpoint, FCM client behind the kill switch (mocked in CI).
3. The wake workflow (only after Vipin chooses), then flip `NOTIFICATIONS_SENDING_ENABLED`.

## Needs Vipin
1. **Firebase project + service account:** create (or reuse) a free Spark-plan Firebase project, add the Android app (package name, `google-services.json` for Android), create a service account with the *Firebase Cloud Messaging API Admin* role, and put its key JSON in Render as `FCM_SERVICE_ACCOUNT_JSON` with `FCM_PROJECT_ID`. Only he can create accounts and keys.
2. **Wake mechanism:** GitHub Actions cron (free while the repo is public), a free external cron service, or in-process only (unreliable). Any external pinger also **reverses the "no keep-alive pinger" choice** and uses most of Render Free's 750 instance-hours.
3. **Default reminder times:** workout 18:00 all days, streak 20:00, water every 120 min 09:00–21:00, food 13:30 and 20:30.
4. **Daily caps** (§4), especially water 8/day and 12 total.
5. **When to turn sending on** (`NOTIFICATIONS_SENDING_ENABLED`), and Android's runtime permission prompt (Android 13+ `POST_NOTIFICATIONS`) wording and timing.

## Open questions for Tech Lead
1. **Drop vs hold:** OK with reminders dropped and events held (coalesced, max 24 h)?
2. **At-most-once** (a stuck `sending` row is never resent) vs at-least-once with possible duplicates?
3. **Workout days as weekdays** chosen by the user, rather than derived from the plan's days-per-week? Should the default be all seven or derived?
4. **Logout cleanup via `refresh_family_id`:** does the access token carry the family id today, or should the device row store the refresh-token family at registration another way?
5. **`reminders` in the existing preferences resource** (one GET/PATCH) vs a separate `/me/notification-reminders` resource?
6. **Internal tick endpoint** outside openapi with a shared secret: acceptable, or should the contract test learn an `x-internal` exemption?
7. **No health numbers in notification text** (lock-screen privacy): agree?
8. Catch-up grace of **30 min** for reminders and **24 h** for events?
