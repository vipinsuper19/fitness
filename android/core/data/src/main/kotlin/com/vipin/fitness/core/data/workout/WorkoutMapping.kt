package com.vipin.fitness.core.data.workout

import com.vipin.fitness.core.data.ExperienceLevel
import com.vipin.fitness.core.network.model.ExerciseDto
import com.vipin.fitness.core.network.model.PlanGenerateRequestDto
import com.vipin.fitness.core.network.model.PlannedExerciseDto
import com.vipin.fitness.core.network.model.PlannedWorkoutDto
import com.vipin.fitness.core.network.model.WorkoutPlanDraftDto
import com.vipin.fitness.core.network.model.WorkoutPlanDto
import java.time.DayOfWeek

fun ExerciseDto.toDomain() = Exercise(
    id = id,
    name = name,
    nameHi = nameHi,
    primaryMuscle = primaryMuscle.toWireEnum<MuscleGroup>(),
    secondaryMuscles = secondaryMuscles.mapNotNull { it.toWireEnum<MuscleGroup>() },
    equipment = equipment.mapNotNull { it.toWireEnum<Equipment>() },
    trackingType = trackingType.toWireEnum<TrackingType>() ?: TrackingType.REPS,
    difficulty = ExperienceLevel.entries.firstOrNull { it.wire == difficulty },
    instructions = instructions,
    videoUrl = videoUrl,
)

private fun PlannedWorkoutDto.toDomain() = PlannedWorkout(
    day = DayOfWeek.of(dayOfWeek),
    name = name,
    exercises = exercises.sortedBy { it.order }.map {
        PlannedExercise(it.exerciseId, it.sets, it.repsMin, it.repsMax, it.durationSeconds, it.targetWeightKg, it.restSeconds, it.notes)
    },
    id = id,
)

private fun PlannedWorkout.toDto() = PlannedWorkoutDto(
    dayOfWeek = day.value,
    name = name,
    exercises = exercises.mapIndexed { i, e ->
        PlannedExerciseDto(e.exerciseId, i, e.sets, e.repsMin, e.repsMax, e.durationSeconds, e.targetWeightKg, e.restSeconds, e.notes)
    },
)

fun WorkoutPlanDraftDto.toDomain() = PlanDraft(
    name = name,
    weeks = weeks,
    source = generator.toWireEnum<PlanSource>() ?: PlanSource.CUSTOM,
    rationale = rationale,
    workouts = workouts.map { it.toDomain() }.sortedBy { it.day },
)

fun PlanDraft.toDto() = WorkoutPlanDraftDto(
    name = name,
    weeks = weeks,
    generator = source.wire,
    rationale = rationale,
    workouts = workouts.sortedBy { it.day }.map { it.toDto() },
)

fun WorkoutPlanDto.toDomain() = WorkoutPlan(
    id = id,
    version = version,
    name = name,
    weeks = weeks,
    rationale = rationale,
    workouts = workouts.map { it.toDomain() }.sortedBy { it.day },
    isActive = isActive,
    source = source.toWireEnum<PlanSource>() ?: PlanSource.CUSTOM,
)

fun PlanGenerateRequest.toDto() = PlanGenerateRequestDto(
    goal = goal.wire,
    experienceLevel = experience.wire,
    daysPerWeek = daysPerWeek,
    sessionMinutes = sessionMinutes,
    equipment = equipment.filter { it != Equipment.NONE }.sortedBy { it.ordinal }.map { it.wire },
    weeks = weeks,
)
