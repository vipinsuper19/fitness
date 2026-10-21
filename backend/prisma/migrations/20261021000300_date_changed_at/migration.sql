-- When an entry's date was last changed by an edit (null = never). Used by the
-- badges backdating rule so a PATCH can't re-date an old entry into a counting
-- window. Additive and nullable; existing rows keep null (createdAt applies).
ALTER TABLE "water_logs" ADD COLUMN "date_changed_at" TIMESTAMPTZ(3);
ALTER TABLE "body_measurements" ADD COLUMN "date_changed_at" TIMESTAMPTZ(3);
