package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.data.ExperienceLevel
import com.vipin.fitness.core.data.workout.Equipment
import com.vipin.fitness.core.data.workout.GoalType
import com.vipin.fitness.core.data.workout.MuscleGroup

/**
 * Display labels for catalog enums. English for now (the catalog itself is English plus Hindi
 * names); move to string resources when the app is localised.
 */
internal fun MuscleGroup.label() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
internal fun Equipment.label() = when (this) {
    Equipment.NONE -> "Bodyweight"
    Equipment.PULL_UP_BAR -> "Pull-up bar"
    else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
internal fun GoalType.label() = when (this) {
    GoalType.LOSE_WEIGHT -> "Lose weight"
    GoalType.GAIN_MUSCLE -> "Build muscle"
    GoalType.MAINTAIN -> "Stay fit"
    GoalType.IMPROVE_ENDURANCE -> "Endurance"
    GoalType.WORKOUTS_PER_WEEK -> "Train consistently"
}
internal fun ExperienceLevel.label() = name.lowercase().replaceFirstChar { it.uppercase() }

internal fun CustomPlanError.message() = when (this) {
    CustomPlanError.NAME -> R.string.err_name
    CustomPlanError.NO_DAYS -> R.string.err_no_days
    CustomPlanError.EMPTY_DAY -> R.string.err_empty_day
    CustomPlanError.BAD_SETS -> R.string.err_sets
    CustomPlanError.BAD_REPS -> R.string.err_reps
    CustomPlanError.BAD_DURATION -> R.string.err_duration
}
