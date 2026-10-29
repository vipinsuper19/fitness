package com.vipin.fitness.core.database.outbox

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The outbox's Retry-After window (v4), per user, so a process restart still waits it out
 * instead of hammering a server that told us to back off. [ownerUserId] is "" when no account is
 * known (writes queued before current_account existed).
 */
@Entity(tableName = "outbox_rate_limit")
data class OutboxRateLimitEntity(
    @PrimaryKey @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    /** Epoch ms before which nothing is sent. */
    @ColumnInfo(name = "not_before_ms") val notBeforeMs: Long,
)
