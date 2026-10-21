-- Custom foods: soft delete keeps rows that diary entries reference (food_logs.food_id is NO ACTION).
ALTER TABLE "food_items" ADD COLUMN "deleted_at" TIMESTAMPTZ(3);

-- Food log entries snapshot the serving their nutrients were computed from.
ALTER TABLE "food_logs"
  ADD COLUMN "serving_size" DECIMAL(8,2),
  ADD COLUMN "serving_unit" "ServingUnit",
  ADD COLUMN "grams_per_serving" DECIMAL(8,2);

UPDATE "food_logs" l
SET "serving_size" = f."serving_size", "serving_unit" = f."serving_unit", "grams_per_serving" = f."grams_per_serving"
FROM "food_items" f
WHERE f."id" = l."food_id";

-- Only custom foods can be deleted; catalog rows stay live.
ALTER TABLE "food_items"
  ADD CONSTRAINT "food_items_deleted_custom_check" CHECK ("deleted_at" IS NULL OR "source" = 'custom');
