package com.vipin.fitness.core.data.session

import com.vipin.fitness.core.data.workout.TrackingType
import java.time.Instant

enum class SessionStatus(val wire: String) {
    IN_PROGRESS("in_progress"), PAUSED("paused"), COMPLETED("completed");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: IN_PROGRESS
    }
}

data class SessionSet(
    val clientSetId: String,
    val setIndex: Int,
    val exerciseId: String,
    val reps: Int?,
    val weightKg: Double?,
    val durationSeconds: Int?,
    val completed: Boolean,
)

data class SessionExercise(
    val position: Int,
    val exerciseId: String,
    val name: String,
    val trackingType: TrackingType,
    val repsMin: Int?,
    val repsMax: Int?,
    val durationSeconds: Int?,
    val targetWeightKg: Double?,
    val restSeconds: Int,
    val skipped: Boolean,
    val replaced: Boolean,
    val sets: List<SessionSet>,
)

data class WorkoutSession(
    val localId: String,
    val name: String,
    val status: SessionStatus,
    val startedAt: Instant,
    val activeMsBeforePause: Long,
    val resumedAt: Instant?,
    val completedAt: Instant?,
    val perceivedDifficulty: Int?,
    val exercises: List<SessionExercise>,
    /** The server has the session (its queued start was sent). */
    val synced: Boolean,
    val xpAwarded: Int?,
    val syncError: String?,
) {
    /** Active (not paused) time at [now]. */
    fun activeSeconds(now: Instant): Long =
        (activeMsBeforePause + (resumedAt?.let { (now.toEpochMilli() - it.toEpochMilli()).coerceAtLeast(0) } ?: 0)) / 1000

    val completedSets get() = exercises.sumOf { e -> e.sets.count { it.completed } }
}

sealed interface FinishOutcome {
    data object Finished : FinishOutcome
    /** Contract: completing needs at least one completed set (422 SESSION_EMPTY). */
    data object NoCompletedSets : FinishOutcome
    data object NoSession : FinishOutcome
}
