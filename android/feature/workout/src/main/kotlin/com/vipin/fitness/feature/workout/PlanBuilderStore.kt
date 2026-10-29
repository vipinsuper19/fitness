package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.data.workout.Exercise
import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlannedExercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import javax.inject.Inject
import com.vipin.fitness.core.data.UserScopedState
import javax.inject.Singleton

/** One exercise in the custom builder. Reps or duration applies depending on the exercise's tracking type. */
data class CustomExercise(
    val exercise: Exercise,
    val sets: Int = 3,
    val repsMin: Int? = if (exercise.trackingType.usesDuration) null else 8,
    val repsMax: Int? = if (exercise.trackingType.usesDuration) null else 12,
    val durationSeconds: Int? = if (exercise.trackingType.usesDuration) 60 else null,
    val restSeconds: Int = 90,
)

data class CustomDay(val name: String, val exercises: List<CustomExercise> = emptyList())

data class CustomPlan(
    val name: String = "",
    val weeks: Int = 4,
    val days: Map<DayOfWeek, CustomDay> = emptyMap(),
)

/**
 * Progress of saving the preview draft. Kept with the draft (not in the ViewModel) so that
 * leaving and reopening Preview after "created but activate failed" only activates, and every
 * retry reuses the same Idempotency-Keys.
 */
data class SaveProgress(val createKey: String? = null, val activateKey: String? = null, val createdPlanId: String? = null)

enum class CustomPlanError { NAME, NO_DAYS, EMPTY_DAY, BAD_SETS, BAD_REPS, BAD_DURATION }

/**
 * Plan-building state shared across screens: the custom plan being built (the exercise library
 * adds to it in pick mode) and the draft shown in preview. In memory only.
 */
@Singleton
class PlanBuilderStore @Inject constructor() : UserScopedState {
    private val _custom = MutableStateFlow(CustomPlan())
    val custom: StateFlow<CustomPlan> = _custom.asStateFlow()

    private val _preview = MutableStateFlow<PlanDraft?>(null)
    val preview: StateFlow<PlanDraft?> = _preview.asStateFlow()

    fun setName(name: String) = _custom.update { it.copy(name = name.take(MAX_NAME)) }
    fun setWeeks(weeks: Int) = _custom.update { it.copy(weeks = weeks.coerceIn(1, 16)) }

    fun toggleDay(day: DayOfWeek, defaultName: String) = _custom.update {
        if (day in it.days) it.copy(days = it.days - day) else it.copy(days = (it.days + (day to CustomDay(defaultName))).toSortedMap())
    }

    fun renameDay(day: DayOfWeek, name: String) = editDay(day) { it.copy(name = name.take(MAX_NAME)) }

    /** From the library in pick mode. Adding to an unselected day selects it. Capped at the schema's 30. */
    fun addExercise(day: DayOfWeek, exercise: Exercise, defaultDayName: String) = _custom.update { p ->
        val d = p.days[day] ?: CustomDay(defaultDayName)
        if (d.exercises.size >= MAX_EXERCISES) {
            p
        } else {
            p.copy(days = (p.days + (day to d.copy(exercises = d.exercises + CustomExercise(exercise)))).toSortedMap())
        }
    }

    fun updateExercise(day: DayOfWeek, index: Int, edit: (CustomExercise) -> CustomExercise) =
        editDay(day) { d -> d.copy(exercises = d.exercises.mapIndexed { i, e -> if (i == index) edit(e) else e }) }

    fun removeExercise(day: DayOfWeek, index: Int) =
        editDay(day) { d -> d.copy(exercises = d.exercises.filterIndexed { i, _ -> i != index }) }

    fun moveExercise(day: DayOfWeek, index: Int, by: Int) = editDay(day) { d ->
        val to = index + by
        if (index !in d.exercises.indices || to !in d.exercises.indices) {
            d
        } else {
            d.copy(exercises = d.exercises.toMutableList().apply { add(to, removeAt(index)) })
        }
    }

    private fun editDay(day: DayOfWeek, f: (CustomDay) -> CustomDay) = _custom.update { p ->
        val d = p.days[day] ?: return@update p
        p.copy(days = (p.days + (day to f(d))).toSortedMap())
    }

    fun resetCustom() {
        _custom.value = CustomPlan()
    }

    private val _saveProgress = MutableStateFlow(SaveProgress())
    val saveProgress: StateFlow<SaveProgress> = _saveProgress.asStateFlow()

    /** A new draft starts a new save: progress belongs to the draft it was made for. */
    fun setPreview(draft: PlanDraft) {
        _preview.value = draft
        _saveProgress.value = SaveProgress()
    }

    fun updateSaveProgress(f: (SaveProgress) -> SaveProgress) = _saveProgress.update(f)

    fun clearPreview() {
        _preview.value = null
        _saveProgress.value = SaveProgress()
    }

    /** Sign-out / sign-in: nothing here belongs to the next user. */
    override suspend fun clearUserState() {
        resetCustom()
        clearPreview()
    }

    companion object {
        const val MAX_NAME = 80
        const val MAX_EXERCISES = 30
    }
}

/** One editable number on an exercise row. */
enum class ExerciseField { SETS, REPS_MIN, REPS_MAX, DURATION }

/** Why one number is invalid; shown inline under its field. */
enum class ExerciseFieldError { SETS_RANGE, REPS_RANGE, REPS_ORDER, DURATION_RANGE }

/** Contract `PlannedExercise` limits (openapi.yaml): sets 1–20, reps 1–100, durationSeconds 1–7200. */
object ExerciseLimits {
    val SETS = 1..20
    val REPS = 1..100
    val DURATION_SECONDS = 1..7200
}

/** Per-field errors for the fields this exercise shows (reps or duration by tracking type). Empty = valid. */
fun CustomExercise.fieldErrors(): Map<ExerciseField, ExerciseFieldError> = buildMap {
    if (sets !in ExerciseLimits.SETS) put(ExerciseField.SETS, ExerciseFieldError.SETS_RANGE)
    if (exercise.trackingType.usesDuration) {
        if (durationSeconds == null || durationSeconds !in ExerciseLimits.DURATION_SECONDS) put(ExerciseField.DURATION, ExerciseFieldError.DURATION_RANGE)
    } else {
        val lo = repsMin
        val hi = repsMax
        if (lo == null || lo !in ExerciseLimits.REPS) put(ExerciseField.REPS_MIN, ExerciseFieldError.REPS_RANGE)
        if (hi == null || hi !in ExerciseLimits.REPS) {
            put(ExerciseField.REPS_MAX, ExerciseFieldError.REPS_RANGE)
        } else if (lo != null && lo in ExerciseLimits.REPS && hi < lo) {
            put(ExerciseField.REPS_MAX, ExerciseFieldError.REPS_ORDER)
        }
    }
}

/** Any exercise number out of range: Preview (the step before Save) stays disabled. */
fun CustomPlan.hasFieldErrors(): Boolean = days.values.any { d -> d.exercises.any { it.fieldErrors().isNotEmpty() } }

/** Validates against the draft schema's limits. */
fun CustomPlan.errors(): Set<CustomPlanError> = buildSet {
    if (name.isBlank()) add(CustomPlanError.NAME)
    if (days.isEmpty()) add(CustomPlanError.NO_DAYS)
    days.values.forEach { d ->
        if (d.name.isBlank()) add(CustomPlanError.NAME)
        if (d.exercises.isEmpty()) add(CustomPlanError.EMPTY_DAY)
        d.exercises.forEach { e ->
            e.fieldErrors().forEach { (f, _) ->
                add(
                    when (f) {
                        ExerciseField.SETS -> CustomPlanError.BAD_SETS
                        ExerciseField.DURATION -> CustomPlanError.BAD_DURATION
                        ExerciseField.REPS_MIN, ExerciseField.REPS_MAX -> CustomPlanError.BAD_REPS
                    },
                )
            }
        }
    }
}

fun CustomPlan.toDraft(): PlanDraft = PlanDraft(
    name = name.trim(),
    weeks = weeks,
    source = PlanSource.CUSTOM,
    rationale = null,
    workouts = days.entries.sortedBy { it.key }.map { (day, d) ->
        PlannedWorkout(
            day = day,
            name = d.name.trim(),
            exercises = d.exercises.map {
                val duration = it.exercise.trackingType.usesDuration
                PlannedExercise(
                    exerciseId = it.exercise.id,
                    sets = it.sets,
                    repsMin = if (duration) null else it.repsMin,
                    repsMax = if (duration) null else it.repsMax,
                    durationSeconds = if (duration) it.durationSeconds else null,
                    restSeconds = it.restSeconds,
                )
            },
        )
    },
)
