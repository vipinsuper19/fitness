package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `WorkoutPlanSummary`. */
@Serializable
data class WorkoutPlanSummaryDto(
    val id: String,
    val name: String,
    val source: String,
    val version: Int,
    /** active | paused | inactive | archived; unknown values read as inactive. */
    val status: String,
    val isActive: Boolean = false,
    val currentWeek: Int? = null,
    val totalWeeks: Int,
    val activatedAt: String? = null,
    val pausedAt: String? = null,
    val archivedAt: String? = null,
    val createdAt: String,
    val completedSessions: Int = 0,
)

@Serializable
data class PlanSummaryPageDto(val items: List<WorkoutPlanSummaryDto>, val nextCursor: String? = null)

/** Always encoded (encodeDefaults is off, so no default here). */
@Serializable
data class PlanActivateDto(val resume: Boolean)

@Serializable
data class PlanRenameDto(val name: String)
