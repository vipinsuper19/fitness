package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Full WorkoutSession (the start/complete DTO only carries what those flows need). */
@Serializable
data class SessionDetailDto(
    val id: String,
    val status: String,
    val startedAt: String,
    val completedAt: String? = null,
    val activeSeconds: Int = 0,
    val planId: String? = null,
    val plannedWorkoutId: String? = null,
    val perceivedDifficulty: Int? = null,
    val notes: String? = null,
    val sets: List<SetLogDto> = emptyList(),
)

@Serializable
data class SetLogDto(
    val id: String,
    val clientSetId: String,
    val exerciseId: String,
    val setIndex: Int,
    val completed: Boolean,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val durationSeconds: Int? = null,
    val distanceM: Double? = null,
)

@Serializable
data class SessionHistoryPageDto(val items: List<SessionDetailDto>, val nextCursor: String? = null)
