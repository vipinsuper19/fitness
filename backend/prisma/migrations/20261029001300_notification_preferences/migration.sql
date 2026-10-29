-- Notification preferences (docs/specs/notification-preferences.md). Storage only; no backfill:
-- users without a row read the defaults.
CREATE TABLE "notification_preferences" (
    "user_id" UUID NOT NULL,
    "enabled" BOOLEAN NOT NULL,
    "types" JSONB NOT NULL DEFAULT '{}',
    "quiet_enabled" BOOLEAN NOT NULL,
    "quiet_start" TIME(0) NOT NULL,
    "quiet_end" TIME(0) NOT NULL,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "notification_preferences_pkey" PRIMARY KEY ("user_id"),
    CONSTRAINT "notification_preferences_quiet_window_check" CHECK ("quiet_start" <> "quiet_end"),
    CONSTRAINT "notification_preferences_types_object_check" CHECK (jsonb_typeof("types") = 'object')
);

ALTER TABLE "notification_preferences" ADD CONSTRAINT "notification_preferences_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
