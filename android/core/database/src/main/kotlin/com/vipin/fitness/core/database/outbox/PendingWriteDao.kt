package com.vipin.fitness.core.database.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingWriteDao {
    /** Re-enqueuing the same idempotency key is a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(write: PendingWriteEntity): Long

    @Query("SELECT * FROM pending_writes ORDER BY created_at, id LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingWriteEntity>

    @Query("SELECT COUNT(*) FROM pending_writes")
    fun count(): Flow<Int>

    @Query("UPDATE pending_writes SET attempts = attempts + 1, last_error = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String?)

    @Query("DELETE FROM pending_writes WHERE id = :id")
    suspend fun delete(id: String)
}
