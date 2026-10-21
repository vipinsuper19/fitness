-- session_correction ledger rows carry a signed XP delta (PRD 5.3), so they may be
-- negative like adjustments. Separate migration: the enum value added in
-- 20261021000500 must be committed before a constraint can reference it.
ALTER TABLE "xp_transactions" DROP CONSTRAINT "xp_transactions_amount_check";
ALTER TABLE "xp_transactions"
  ADD CONSTRAINT "xp_transactions_amount_check" CHECK ("amount" <> 0 AND ("source_type" IN ('adjustment', 'session_correction') OR "amount" > 0));
