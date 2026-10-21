package com.vipin.fitness.core.database.outbox

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A write made offline (set log, food log...) waiting to be sent. The [idempotencyKey] is
 * generated once when the user acts and reused on every retry, so the server can dedupe
 * (contract: required on writes that can award XP or are retried offline). WorkManager drains
 * this table in T13/T14.
 */
@Entity(
    tableName = "pending_writes",
    indices = [Index(value = ["idempotency_key"], unique = true), Index("created_at")],
)
data class PendingWriteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    /** HTTP method, e.g. POST. */
    val method: String,
    /** Path relative to /api/v1/, e.g. workout-sessions/{id}/sets. */
    val path: String,
    /** JSON request body. */
    val body: String?,
    @ColumnInfo(name = "created_at") val createdAtEpochMs: Long,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /** User who made the write (v2). Never sent for anyone else; null = unknown (v1 rows). */
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String? = null,
)
