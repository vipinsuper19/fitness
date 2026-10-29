-- Optional daily macro targets (grams). Additive, nullable, no backfill. 0 is stored as null by the API.
ALTER TABLE "user_profiles"
  ADD COLUMN "daily_protein_target_g" INTEGER,
  ADD COLUMN "daily_carbs_target_g" INTEGER,
  ADD COLUMN "daily_fat_target_g" INTEGER;
