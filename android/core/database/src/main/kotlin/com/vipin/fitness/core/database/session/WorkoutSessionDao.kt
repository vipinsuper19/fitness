package com.vipin.fitness.core.database.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutSessionDao {
    @Insert suspend fun insertSession(s: WorkoutSessionEntity)
    @Insert suspend fun insertExercises(e: List<SessionExerciseEntity>)
    @Insert suspend fun insertSets(s: List<SessionSetEntity>)
    @Update suspend fun updateSession(s: WorkoutSessionEntity)
    @Update suspend fun updateExercise(e: SessionExerciseEntity)
    @Update suspend fun updateSet(s: SessionSetEntity)

    /** The one unfinished session, if any. */
    @Query("SELECT * FROM workout_sessions WHERE status != 'completed' ORDER BY started_at DESC LIMIT 1")
    fun observeUnfinished(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE status != 'completed' ORDER BY started_at DESC LIMIT 1")
    suspend fun unfinished(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE local_id = :localId")
    fun observeSession(localId: String): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE local_id = :localId")
    suspend fun session(localId: String): WorkoutSessionEntity?

    @Query("SELECT * FROM session_exercises WHERE session_local_id = :localId ORDER BY position")
    fun observeExercises(localId: String): Flow<List<SessionExerciseEntity>>

    @Query("SELECT * FROM session_exercises WHERE session_local_id = :localId AND position = :position")
    suspend fun exercise(localId: String, position: Int): SessionExerciseEntity?

    @Query("SELECT * FROM session_sets WHERE session_local_id = :localId ORDER BY position, set_index")
    fun observeSets(localId: String): Flow<List<SessionSetEntity>>

    @Query("SELECT * FROM session_sets WHERE session_local_id = :localId ORDER BY position, set_index")
    suspend fun sets(localId: String): List<SessionSetEntity>

    @Query("SELECT * FROM session_sets WHERE client_set_id = :clientSetId")
    suspend fun set(clientSetId: String): SessionSetEntity?

    @Query("UPDATE workout_sessions SET server_id = :serverId WHERE start_key = :startKey")
    suspend fun setServerId(startKey: String, serverId: String): Int

    @Query("UPDATE workout_sessions SET xp_awarded = :xp WHERE complete_key = :completeKey")
    suspend fun setXpAwarded(completeKey: String, xp: Int): Int

    @Query("SELECT * FROM workout_sessions WHERE start_key = :startKey")
    suspend fun byStartKey(startKey: String): WorkoutSessionEntity?

    @Query("SELECT server_id FROM workout_sessions WHERE local_id = :localId")
    suspend fun serverId(localId: String): String?

    /** Keeps the first error: later ones are usually its consequence. */
    @Query("UPDATE workout_sessions SET sync_error = COALESCE(sync_error, :error) WHERE local_id = :localId")
    suspend fun setSyncError(localId: String, error: String?)

    @Query("SELECT * FROM workout_sessions WHERE sync_error IS NOT NULL ORDER BY started_at DESC")
    fun observeFlagged(): Flow<List<WorkoutSessionEntity>>

    @Query("UPDATE workout_sessions SET sync_error = NULL WHERE local_id = :localId")
    suspend fun clearSyncError(localId: String)

    @Query("SELECT * FROM workout_sessions")
    suspend fun all(): List<WorkoutSessionEntity>

    @Query("SELECT user_id FROM current_account WHERE id = 1")
    suspend fun currentUserId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCurrentAccount(a: CurrentAccountEntity)

    @Query("DELETE FROM current_account")
    suspend fun clearCurrentAccount()

    /** Sign-in as someone else: their sessions (and unknown-owner ones) go, unsent. */
    @Query("DELETE FROM workout_sessions WHERE owner_user_id IS NULL OR owner_user_id != :userId")
    suspend fun deleteNotOwnedBy(userId: String)

    /** Sign-out: sessions belong to the user. Exercises and sets go by cascade. */
    @Query("DELETE FROM workout_sessions")
    suspend fun clear()
}
