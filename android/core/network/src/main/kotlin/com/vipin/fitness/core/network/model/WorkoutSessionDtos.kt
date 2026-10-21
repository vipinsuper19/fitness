package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionStartDto(
    val plannedWorkoutId: String? = null,
    val planVersion: Int? = null,
    /** Client time for offline starts (contract: within the last 24 h). */
    val startedAt: String? = null,
)

@Serializable
data class SessionUpdateDto(val status: String? = null)

@Serializable
data class SessionCompleteDto(
    val completedAt: String? = null,
    val perceivedDifficulty: Int? = null,
    val notes: String? = null,
)

/** Nullable fields are sent as explicit nulls only when set; defaults are omitted. */
@Serializable
data class SetLogUpsertDto(
    val clientSetId: String,
    val exerciseId: String,
    val setIndex: Int,
    val completed: Boolean,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val durationSeconds: Int? = null,
    val loggedAt: String? = null,
)

@Serializable
data class WorkoutSessionDto(
    val id: String,
    val status: String,
    val startedAt: String,
    val activeSeconds: Int,
)

@Serializable
data class SessionCompleteResultDto(val session: WorkoutSessionDto, val xpAwarded: Int)
