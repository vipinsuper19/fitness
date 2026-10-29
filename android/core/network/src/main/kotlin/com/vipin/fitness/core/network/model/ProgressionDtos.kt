package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** GET /workout-plans/{planId}/progression-suggestions (docs/specs/adaptive-progression.md). */
@Serializable
data class ProgressionSuggestionsDto(
    val planId: String,
    val planVersion: Int,
    val generatedAt: String,
    val windowSessions: Int = 3,
    val suggestions: List<ProgressionSuggestionDto>,
)

/** `inputs` is nullable: servers before #146 omit it. */
@Serializable
data class ProgressionSuggestionDto(
    val plannedWorkoutId: String,
    val exerciseId: String,
    val order: Int,
    val trackingType: String,
    val action: String,
    val reason: String,
    val reasonText: String? = null,
    val current: PrescriptionValuesDto,
    val suggested: PrescriptionValuesDto? = null,
    val harderVariations: List<String>? = null,
    val cooldownUntil: String? = null,
    val basedOn: List<ProgressionBasisDto> = emptyList(),
    val inputs: ProgressionInputsDto? = null,
)

/** Machine-readable inputs behind `reason`; every field tolerant (unknown fields are ignored by fitnessJson). */
@Serializable
data class ProgressionInputsDto(
    val sessionsRequired: Int? = null,
    val sessionsConsidered: Int? = null,
    val sessionsMetTarget: Int? = null,
    val maxPerceivedDifficulty: Int? = null,
    val cooldown: ProgressionCooldownDto? = null,
    val step: ProgressionStepDto? = null,
)

@Serializable
data class ProgressionCooldownDto(val active: Boolean = false, val lastRaisedAt: String? = null, val until: String? = null, val days: Int? = null)

@Serializable
data class ProgressionStepDto(
    val field: String? = null,
    val rule: String? = null,
    val percent: Double? = null,
    val capPercent: Double? = null,
    val capApplied: Boolean = false,
    val minStepApplied: Boolean = false,
    val rounding: String? = null,
    val limitApplied: Boolean = false,
)

@Serializable
data class PrescriptionValuesDto(
    val sets: Int,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val targetWeightKg: Double? = null,
    val durationSeconds: Int? = null,
)

@Serializable
data class ProgressionBasisDto(
    val sessionId: String,
    val completedAt: String,
    val perceivedDifficulty: Int? = null,
    val completedSets: Int,
    val plannedSets: Int,
    val metTarget: Boolean,
)

/**
 * PATCH /workout-plans/{planId} body (`WorkoutPlanUpdate`). Every field is sent explicitly, nulls
 * included (no defaults, so `encodeDefaults = false` drops nothing): the body replaces the plan.
 */
@Serializable
data class WorkoutPlanUpdateDto(
    val baseVersion: Int,
    val weeks: Int,
    val generator: String,
    val workouts: List<PlannedWorkoutUpdateDto>,
    /** Omitted when the plan has none (as in the spec's applySuggestions example). */
    val rationale: String? = null,
)

@Serializable
data class PlannedWorkoutUpdateDto(val dayOfWeek: Int, val name: String, val exercises: List<PlannedExerciseUpdateDto>)

@Serializable
data class PlannedExerciseUpdateDto(
    val exerciseId: String,
    val order: Int,
    val sets: Int,
    val repsMin: Int?,
    val repsMax: Int?,
    val targetWeightKg: Double?,
    val durationSeconds: Int?,
    val restSeconds: Int,
    val notes: String?,
)

/** Error body with `details` (e.g. VERSION_CONFLICT's `currentVersion`). */
@Serializable
data class ErrorWithDetailsDto(val code: String, val message: String = "", val requestId: String? = null, val details: JsonObject? = null)
