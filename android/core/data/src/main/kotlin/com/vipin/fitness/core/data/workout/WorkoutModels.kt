package com.vipin.fitness.core.data.workout

import com.vipin.fitness.core.data.ExperienceLevel
import java.time.DayOfWeek

/** Wire value <-> enum helpers. Unknown wire values from a newer server map to null and are skipped. */
interface Wire { val wire: String }

inline fun <reified E> String.toWireEnum(): E? where E : Enum<E>, E : Wire =
    enumValues<E>().firstOrNull { it.wire == this }

enum class MuscleGroup(override val wire: String) : Wire {
    CHEST("chest"), BACK("back"), SHOULDERS("shoulders"), BICEPS("biceps"), TRICEPS("triceps"),
    FOREARMS("forearms"), CORE("core"), QUADS("quads"), HAMSTRINGS("hamstrings"), GLUTES("glutes"),
    CALVES("calves"), FULL_BODY("full_body"), CARDIO("cardio"),
}

enum class Equipment(override val wire: String) : Wire {
    NONE("none"), DUMBBELL("dumbbell"), BARBELL("barbell"), KETTLEBELL("kettlebell"), MACHINE("machine"),
    CABLE("cable"), RESISTANCE_BAND("resistance_band"), BENCH("bench"), PULL_UP_BAR("pull_up_bar"), OTHER("other"),
}

enum class TrackingType(override val wire: String) : Wire {
    REPS_WEIGHT("reps_weight"), REPS("reps"), DURATION("duration"), DISTANCE_DURATION("distance_duration");

    val usesDuration get() = this == DURATION || this == DISTANCE_DURATION
}

enum class GoalType(override val wire: String) : Wire {
    LOSE_WEIGHT("lose_weight"), GAIN_MUSCLE("gain_muscle"), MAINTAIN("maintain"),
    IMPROVE_ENDURANCE("improve_endurance"), WORKOUTS_PER_WEEK("workouts_per_week"),
}

enum class PlanSource(override val wire: String) : Wire { RULE_BASED("rule_based"), CUSTOM("custom") }

data class Exercise(
    val id: String,
    val name: String,
    val nameHi: String?,
    val primaryMuscle: MuscleGroup?,
    val secondaryMuscles: List<MuscleGroup>,
    val equipment: List<Equipment>,
    val trackingType: TrackingType,
    val difficulty: ExperienceLevel?,
    val instructions: List<String>,
    val videoUrl: String?,
)

data class ExercisePage(val items: List<Exercise>, val nextCursor: String?)

data class ExerciseFilter(val query: String = "", val muscle: MuscleGroup? = null, val equipment: Equipment? = null)

data class PlanGenerateRequest(
    val goal: GoalType,
    val experience: ExperienceLevel,
    val daysPerWeek: Int,
    val sessionMinutes: Int,
    val equipment: Set<Equipment>,
    val weeks: Int = 4,
)

data class PlannedExercise(
    val exerciseId: String,
    val sets: Int,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val durationSeconds: Int? = null,
    val targetWeightKg: Double? = null,
    val restSeconds: Int = 90,
    val notes: String? = null,
)

/** One day's workout. [day] is the ISO weekday. Exercise order is the list order. */
data class PlannedWorkout(val day: DayOfWeek, val name: String, val exercises: List<PlannedExercise>)

/** Unsaved plan: from the generator or built by hand. */
data class PlanDraft(
    val name: String,
    val weeks: Int,
    val source: PlanSource,
    val rationale: String?,
    val workouts: List<PlannedWorkout>,
)

data class WorkoutPlan(
    val id: String,
    val version: Int,
    val name: String,
    val weeks: Int,
    val rationale: String?,
    val workouts: List<PlannedWorkout>,
    val isActive: Boolean,
    val source: PlanSource,
) {
    fun workoutOn(day: DayOfWeek): PlannedWorkout? = workouts.firstOrNull { it.day == day }
}
