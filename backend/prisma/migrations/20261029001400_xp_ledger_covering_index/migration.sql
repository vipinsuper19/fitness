-- Index audit (docs/specs/index-audit.md): the XP ledger index gains `id` (the ledger's tie-break,
-- so a page is read in index order with no sort) and INCLUDEs amount and source_type, so the
-- balance and achievement sums are index-only scans instead of one heap page per ledger row.
-- Replaces xp_transactions_user_id_created_at_idx, whose key is a prefix of the new one.
-- No data change. Plain CREATE INDEX (Prisma runs migrations in a transaction): it blocks writes
-- to xp_transactions while it builds.
CREATE INDEX "xp_transactions_user_id_created_at_id_idx" ON "xp_transactions" ("user_id", "created_at", "id") INCLUDE ("amount", "source_type");
DROP INDEX "xp_transactions_user_id_created_at_idx";
