package com.vipin.fitness.core.database.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A workout session as recorded on the device. It exists before the server knows about it
 * (offline start): [localId] is ours, [serverId] arrives when the queued start is synced.
 * Active time is [activeMsBeforePause] plus, while running, now − [resumedAtMs].
 */
@Entity(tableName = "workout_sessions", indices = [Index("status"), Index(value = ["start_key"], unique = true)])
data class WorkoutSessionEntity(
    @PrimaryKey @ColumnInfo(name = "local_id") val localId: String,
    @ColumnInfo(name = "server_id") val serverId: String? = null,
    /** Idempotency-Key of the queued start; the sync matches its response back to this row. */
    @ColumnInfo(name = "start_key") val startKey: String,
    @ColumnInfo(name = "complete_key") val completeKey: String? = null,
    @ColumnInfo(name = "plan_id") val planId: String?,
    @ColumnInfo(name = "plan_version") val planVersion: Int?,
    @ColumnInfo(name = "planned_workout_id") val plannedWorkoutId: String?,
    val name: String,
    /** in_progress, paused or completed (contract SessionStatus). */
    val status: String,
    @ColumnInfo(name = "started_at") val startedAtMs: Long,
    @ColumnInfo(name = "active_ms_before_pause") val activeMsBeforePause: Long = 0,
    /** Set while running, null while paused or completed. */
    @ColumnInfo(name = "resumed_at") val resumedAtMs: Long?,
    @ColumnInfo(name = "completed_at") val completedAtMs: Long? = null,
    @ColumnInfo(name = "perceived_difficulty") val perceivedDifficulty: Int? = null,
    val notes: String? = null,
    /** From the server once the queued completion is synced. */
    @ColumnInfo(name = "xp_awarded") val xpAwarded: Int? = null,
    /** A queued write the server rejected for good (e.g. a start older than 24 h). */
    @ColumnInfo(name = "sync_error") val syncError: String? = null,
)

/** One exercise slot of a session, in order. Skip and replace change the slot, not the plan. */
@Entity(
    tableName = "session_exercises",
    primaryKeys = ["session_local_id", "position"],
    foreignKeys = [ForeignKey(WorkoutSessionEntity::class, ["local_id"], ["session_local_id"], onDelete = ForeignKey.CASCADE)],
)
data class SessionExerciseEntity(
    @ColumnInfo(name = "session_local_id") val sessionLocalId: String,
    val position: Int,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "exercise_name") val exerciseName: String,
    /** Contract TrackingType wire value. */
    @ColumnInfo(name = "tracking_type") val trackingType: String,
    @ColumnInfo(name = "target_sets") val targetSets: Int,
    @ColumnInfo(name = "reps_min") val repsMin: Int?,
    @ColumnInfo(name = "reps_max") val repsMax: Int?,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int?,
    @ColumnInfo(name = "target_weight_kg") val targetWeightKg: Double?,
    @ColumnInfo(name = "rest_seconds") val restSeconds: Int,
    val skipped: Boolean = false,
    /** Planned exercise id when this slot was replaced. */
    @ColumnInfo(name = "replaced_exercise_id") val replacedExerciseId: String? = null,
)

/** One set. [clientSetId] is the contract's upsert key, so re-sending it replaces the set. */
@Entity(
    tableName = "session_sets",
    indices = [Index(value = ["session_local_id", "position", "set_index"], unique = true)],
    foreignKeys = [ForeignKey(WorkoutSessionEntity::class, ["local_id"], ["session_local_id"], onDelete = ForeignKey.CASCADE)],
)
data class SessionSetEntity(
    @PrimaryKey @ColumnInfo(name = "client_set_id") val clientSetId: String,
    @ColumnInfo(name = "session_local_id") val sessionLocalId: String,
    val position: Int,
    @ColumnInfo(name = "set_index") val setIndex: Int,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val reps: Int? = null,
    @ColumnInfo(name = "weight_kg") val weightKg: Double? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int? = null,
    val completed: Boolean = false,
    @ColumnInfo(name = "logged_at") val loggedAtMs: Long? = null,
    /** Values changed after the set was last queued; re-queued before pause/finish. */
    @ColumnInfo(name = "needs_sync") val needsSync: Boolean = false,
)
