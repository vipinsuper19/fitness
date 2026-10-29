package com.vipin.fitness.feature.workout

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vipin.fitness.core.data.ExperienceLevel
import com.vipin.fitness.core.data.workout.Equipment
import com.vipin.fitness.core.data.workout.GoalType
import com.vipin.fitness.core.data.workout.MuscleGroup

/** Display labels for catalog enums, from string resources. */
@StringRes internal fun MuscleGroup.labelRes() = when (this) {
    MuscleGroup.CHEST -> R.string.muscle_chest
    MuscleGroup.BACK -> R.string.muscle_back
    MuscleGroup.SHOULDERS -> R.string.muscle_shoulders
    MuscleGroup.BICEPS -> R.string.muscle_biceps
    MuscleGroup.TRICEPS -> R.string.muscle_triceps
    MuscleGroup.FOREARMS -> R.string.muscle_forearms
    MuscleGroup.CORE -> R.string.muscle_core
    MuscleGroup.QUADS -> R.string.muscle_quads
    MuscleGroup.HAMSTRINGS -> R.string.muscle_hamstrings
    MuscleGroup.GLUTES -> R.string.muscle_glutes
    MuscleGroup.CALVES -> R.string.muscle_calves
    MuscleGroup.FULL_BODY -> R.string.muscle_full_body
    MuscleGroup.CARDIO -> R.string.muscle_cardio
}

@StringRes internal fun Equipment.labelRes() = when (this) {
    Equipment.NONE -> R.string.equipment_none
    Equipment.DUMBBELL -> R.string.equipment_dumbbell
    Equipment.BARBELL -> R.string.equipment_barbell
    Equipment.KETTLEBELL -> R.string.equipment_kettlebell
    Equipment.MACHINE -> R.string.equipment_machine
    Equipment.CABLE -> R.string.equipment_cable
    Equipment.RESISTANCE_BAND -> R.string.equipment_resistance_band
    Equipment.BENCH -> R.string.equipment_bench
    Equipment.PULL_UP_BAR -> R.string.equipment_pull_up_bar
    Equipment.OTHER -> R.string.equipment_other
}

@StringRes internal fun GoalType.labelRes() = when (this) {
    GoalType.LOSE_WEIGHT -> R.string.goal_lose_weight
    GoalType.GAIN_MUSCLE -> R.string.goal_gain_muscle
    GoalType.MAINTAIN -> R.string.goal_maintain
    GoalType.IMPROVE_ENDURANCE -> R.string.goal_improve_endurance
    GoalType.WORKOUTS_PER_WEEK -> R.string.goal_workouts_per_week
}

@StringRes internal fun ExperienceLevel.labelRes() = when (this) {
    ExperienceLevel.BEGINNER -> R.string.level_beginner
    ExperienceLevel.INTERMEDIATE -> R.string.level_intermediate
    ExperienceLevel.ADVANCED -> R.string.level_advanced
}

@Composable internal fun MuscleGroup.label() = stringResource(labelRes())
@Composable internal fun Equipment.label() = stringResource(labelRes())
@Composable internal fun GoalType.label() = stringResource(labelRes())
@Composable internal fun ExperienceLevel.label() = stringResource(labelRes())

internal fun ExerciseFieldError.message() = when (this) {
    ExerciseFieldError.SETS_RANGE -> R.string.err_field_sets
    ExerciseFieldError.REPS_RANGE -> R.string.err_field_reps
    ExerciseFieldError.REPS_ORDER -> R.string.err_field_reps_order
    ExerciseFieldError.DURATION_RANGE -> R.string.err_field_duration
}

internal fun CustomPlanError.message() = when (this) {
    CustomPlanError.NAME -> R.string.err_name
    CustomPlanError.NO_DAYS -> R.string.err_no_days
    CustomPlanError.EMPTY_DAY -> R.string.err_empty_day
    CustomPlanError.BAD_SETS -> R.string.err_sets
    CustomPlanError.BAD_REPS -> R.string.err_reps
    CustomPlanError.BAD_DURATION -> R.string.err_duration
}
