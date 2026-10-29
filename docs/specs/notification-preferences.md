# Notification preferences (contract)

Users choose **which kinds of notification** they want and a **quiet-hours** window. This spec covers **storage and the API only**.

**Out of scope (explicitly):** sending anything. No FCM, no device tokens or registration, no scheduler, no delivery, no in-app inbox, no email or SMS. Nothing in the backend reads these preferences yet. A later sending spec will consume them and will define per-type settings such as reminder times, the water interval and workout days (decision 3). Until then, storing a preference has no visible effect besides `GET` returning it.

## Types
One opt-in flag per type, each tied to an existing feature:

| type | feature | default |
|---|---|---|
| `workout_reminder` | planned workouts (workout plans, sessions) | **on** |
| `streak_reminder` | workout streak about to break (progress summary streaks) | **on** |
| `water_reminder` | water logging toward `dailyWaterTargetMl` | off |
| `food_log_reminder` | food diary logging (food logs, nutrition summary) | off |
| `challenge_updates` | challenge progress, deadline near, completed, expired | **on** |
| `badge_earned` | a badge was awarded | **on** |
| `progression_suggestion` | adaptive progression has a new suggestion for an exercise | **on** |

- Water and food reminders are **off** by default because they are frequent; the rest are low-volume and tied to things the user chose (a plan, a challenge).
- A master switch `enabled` (default **on**) turns everything off without losing the per-type choices. A type is effectively on only when `enabled` **and** its flag are true.
- **New types:** adding a type is a contract change that also sets its default. Users who never set it get that default. Clients must ignore type keys they don't know in responses.
- **Removed types:** a stored flag for a type no longer in the catalog is ignored and dropped on the next write. It is never returned. A PATCH naming a removed type is an unknown type and gets `400 VALIDATION_FAILED` (`types.<key>`).

## Quiet hours
`quietHours: { enabled, start, end }` with `start` and `end` as local wall-clock times `HH:MM` (24 h, `00:00`–`23:59`). Default **enabled, 22:00 → 07:00**.

- **Timezone:** always the **profile timezone** (`user_profiles.timezone`, default `Asia/Kolkata`) **at the moment a notification would be sent**. Nothing is snapshotted. After a zone change (via `PUT /me/profile`), the same wall-clock window applies in the new zone immediately. Example: 22:00–07:00 means 22:00–07:00 in Kolkata, and after moving to `America/New_York`, 22:00–07:00 New York time.
- **Window:** `[start, end)`. If `start < end`, it's within one local day (13:00–15:00). If `start > end`, it **crosses midnight**: 22:00–07:00 covers 22:00–23:59 and 00:00–06:59. `start == end` is invalid (`VALIDATION_FAILED`); to turn quiet hours off, set `enabled: false`.
- **The check is by wall clock:** a local time `t` is quiet when it falls within the window above. It's evaluated with the current local time in the profile zone, so it doesn't depend on the date.
- **DST:** because the check uses local wall time, a window stays at the same wall-clock hours across a DST change, so its length in real time can be one hour longer or shorter that night. A wall time that is skipped (spring forward) just doesn't occur; an hour that repeats (fall back) is checked each time it occurs, so it is quiet or not by the same rule. `Asia/Kolkata` has no DST; the rule matters for other zones.
- **Every type is subject to quiet hours**; nothing bypasses them, including badges and challenges (decision 4).
- **What happens to a notification inside the window** (drop, or hold until `end`) is decided by the sending spec (decision 2).

## Defaults and storage
- **No row means defaults.** A user who has never saved preferences reads the defaults above. `GET` never writes. New users get no row at sign-up.
- Table `notification_preferences` (planned, not in this PR): `user_id` uuid PK, FK → users `ON DELETE CASCADE`; `enabled` bool; `types` jsonb (`{type: bool}`, only for the types the user has set); `quiet_enabled` bool; `quiet_start`, `quiet_end` time; `updated_at` timestamptz.
- Reads merge the stored flags over the current defaults, so a type added later shows its default to everyone who hasn't set it.
- **Export:** `GET /me/export` includes `notificationPreferences` (the same shape as `GET`, defaults included). It's required in the `DataExport` schema. **Deletion:** removed with the account (cascade).

## API
`GET /me/notification-preferences` → `200 NotificationPreferences`:
```json
{ "enabled": true,
  "types": { "workout_reminder": true, "streak_reminder": true, "water_reminder": false, "food_log_reminder": false,
             "challenge_updates": true, "badge_earned": true, "progression_suggestion": true },
  "quietHours": { "enabled": true, "start": "22:00", "end": "07:00" },
  "timezone": "Asia/Kolkata",
  "updatedAt": null }
```
- `types` always holds **every** current type. `timezone` is the profile timezone that quiet hours use (read-only, echoed for display). `updatedAt` is null until the first save.

`PATCH /me/notification-preferences` with `NotificationPreferencesUpdate` → `200 NotificationPreferences` (the full merged result):
- A **partial merge**: omitted fields keep their value. `types` merges key by key, so `{"types": {"water_reminder": true}}` changes only that flag. `quietHours` merges field by field, and the resulting `start` and `end` are validated together.
- `Idempotency-Key` is **optional**, as on `PUT /me/profile`, with the usual replay rules (`409` on reuse with a different body). The merge is idempotent anyway. A PATCH that changes nothing still returns 200 and doesn't change `updatedAt`.
- PUT isn't offered: a full replace would make older clients reset types they don't know about.

**Validation** (`400 VALIDATION_FAILED`, `details.fields` keyed by path; nothing is saved if any field fails):
- **Unknown type key** (including a removed type) → `types.<key>: "unknown notification type"`. It's rejected, not ignored, so a typo can't silently do nothing. Clients send only the types they got from `GET`.
- **Non-boolean** `enabled`, `types.*` or `quietHours.enabled`; **null** for any field.
- **`start` or `end`** not matching `^([01]\d|2[0-3]):[0-5]\d$` → `quietHours.start` or `quietHours.end`.
- **`start == end`** after the merge → `quietHours: "start and end must differ"`.
- **Unknown top-level or `quietHours` property** (including the read-only `timezone` and `updatedAt`) → rejected as unknown, like other request bodies.
- **Empty body `{}`** is valid and is a no-op.

Errors: 401 as usual, 409 for Idempotency-Key conflicts, and `429 RATE_LIMITED` on both GET and PATCH (per-user limit, counted separately for each route: `NOTIFICATION_PREFS_RATE_LIMIT_PER_MINUTE`, default 60, like the other `/me` endpoints). There is no 404 (a missing row reads as defaults).

## Tests (for the implementation PR)
- GET with no row returns the defaults and writes nothing; a new user has no row.
- PATCH partial merge: a single type, the master switch, `quietHours.start` only. Other values are unchanged; `updatedAt` is set; a no-op PATCH keeps `updatedAt`.
- The response always lists every type; a stored flag for a removed type isn't returned. A type added later shows its default.
- Validation: unknown type (`types.foo`), non-boolean, null, bad `HH:MM` (`24:00`, `7:00`, `07:60`), `start == end` (including when only one side is sent and the merge makes them equal), unknown property, read-only fields. Nothing is saved on a 400.
- Quiet-hours helper (pure, for the sender): same-day window; a cross-midnight window at 21:59, 22:00, 06:59 and 07:00; zone change (a 22:00 Kolkata window evaluated after moving to New York uses New York wall time); DST spring-forward and fall-back nights in `America/New_York` (the repeated 01:xx hour is quiet in both occurrences for a 01:00–02:00 window).
- Export includes `notificationPreferences`, defaults included; deleting the account removes the row; the schemas validate against openapi.

## Decisions
1. **Defaults approved:** on for workout, streak, challenge, badge and progression; off for water and food; master switch on; quiet hours on, 22:00–07:00.
2. **Drop or hold in quiet hours:** deferred to the sending spec. This spec only stores the window.
3. **Reminder times, the water interval and workout days** belong in the sending spec, not here.
4. **Nothing bypasses quiet hours**, including badges and challenges: every type is subject to them.
5. **Unknown type in PATCH:** `400 VALIDATION_FAILED`, not ignored.
6. **PATCH only, no PUT.**
