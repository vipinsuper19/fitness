-- Session correction (PRD 5.3, docs/specs/session-correction.md): one row per
-- correction or discard of a completed session, each with its own ledger key.
-- session_id is intentionally not a foreign key (a discard deletes the session).
-- CreateEnum
CREATE TYPE "SessionCorrectionKind" AS ENUM ('correction', 'discard');

-- AlterEnum
ALTER TYPE "XpSourceType" ADD VALUE 'session_correction';

-- CreateTable
CREATE TABLE "session_corrections" (
    "id" UUID NOT NULL,
    "session_id" UUID NOT NULL,
    "user_id" UUID NOT NULL,
    "kind" "SessionCorrectionKind" NOT NULL,
    "xp_delta" INTEGER NOT NULL,
    "idempotency_key" VARCHAR(255) NOT NULL,
    "created_at" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "session_corrections_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "session_corrections_session_id_idx" ON "session_corrections"("session_id");

-- CreateIndex
CREATE UNIQUE INDEX "session_corrections_user_id_idempotency_key_key" ON "session_corrections"("user_id", "idempotency_key");

-- AddForeignKey
ALTER TABLE "session_corrections" ADD CONSTRAINT "session_corrections_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

