package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/* Exercises and workout plans, from contracts/openapi.yaml and schemas/workout-plan-draft.schema.json. */

@Serializable
data class ExerciseDto(
    val id: String,
    val slug: String,
    val name: String,
    val nameHi: String? = null,
    val primaryMuscle: String,
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val trackingType: String,
    val difficulty: String,
    val instructions: List<String> = emptyList(),
    val videoUrl: String? = null,
)

@Serializable
data class ExercisePageDto(val items: List<ExerciseDto>, val nextCursor: String? = null)

@Serializable
data class PlanGenerateRequestDto(
    val goal: String,
    val experienceLevel: String,
    val daysPerWeek: Int,
    val sessionMinutes: Int,
    val equipment: List<String> = emptyList(),
    val weeks: Int = 4,
)

@Serializable
data class PlannedExerciseDto(
    val exerciseId: String,
    val order: Int,
    val sets: Int,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val durationSeconds: Int? = null,
    val targetWeightKg: Double? = null,
    val restSeconds: Int = 90,
    val notes: String? = null,
)

/** In a draft `id` is absent (the schema forbids extra keys, and Json omits null defaults). */
@Serializable
data class PlannedWorkoutDto(
    val id: String? = null,
    val dayOfWeek: Int,
    val name: String,
    val exercises: List<PlannedExerciseDto>,
)

@Serializable
data class WorkoutPlanDraftDto(
    val name: String,
    val weeks: Int,
    val generator: String,
    val rationale: String? = null,
    val workouts: List<PlannedWorkoutDto>,
)

@Serializable
data class WorkoutPlanDto(
    val id: String,
    val version: Int,
    val name: String,
    val weeks: Int,
    val rationale: String? = null,
    val workouts: List<PlannedWorkoutDto>,
    val isActive: Boolean,
    val source: String,
    val createdAt: String,
)
