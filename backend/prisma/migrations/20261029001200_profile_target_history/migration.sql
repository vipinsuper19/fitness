-- Dated daily-target history (docs/specs/target-history.md). Additive.
CREATE TABLE "profile_target_history" (
    "user_id" UUID NOT NULL,
    "effective_from" DATE NOT NULL,
    "daily_calorie_target_kcal" INTEGER,
    "daily_water_target_ml" INTEGER,
    "daily_protein_target_g" INTEGER,
    "daily_carbs_target_g" INTEGER,
    "daily_fat_target_g" INTEGER,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(3) NOT NULL,

    CONSTRAINT "profile_target_history_pkey" PRIMARY KEY ("user_id","effective_from")
);

ALTER TABLE "profile_target_history" ADD CONSTRAINT "profile_target_history_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Idempotent backfill: one 1900-01-01 row with the current targets for every profile that has any
-- target (0 counts as none), so past periods are unchanged at migration time.
INSERT INTO "profile_target_history" ("user_id", "effective_from", "daily_calorie_target_kcal", "daily_water_target_ml",
  "daily_protein_target_g", "daily_carbs_target_g", "daily_fat_target_g", "updated_at")
SELECT "user_id", DATE '1900-01-01',
  NULLIF("daily_calorie_target_kcal", 0), NULLIF("daily_water_target_ml", 0),
  NULLIF("daily_protein_target_g", 0), NULLIF("daily_carbs_target_g", 0), NULLIF("daily_fat_target_g", 0),
  CURRENT_TIMESTAMP
FROM "user_profiles"
WHERE COALESCE(NULLIF("daily_calorie_target_kcal", 0), NULLIF("daily_water_target_ml", 0), NULLIF("daily_protein_target_g", 0),
  NULLIF("daily_carbs_target_g", 0), NULLIF("daily_fat_target_g", 0)) IS NOT NULL
ON CONFLICT ("user_id", "effective_from") DO NOTHING;
