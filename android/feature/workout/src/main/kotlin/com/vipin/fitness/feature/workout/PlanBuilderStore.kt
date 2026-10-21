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

enum class CustomPlanError { NAME, NO_DAYS, EMPTY_DAY, BAD_SETS, BAD_REPS, BAD_DURATION }

/**
 * Plan-building state shared across screens: the custom plan being built (the exercise library
 * adds to it in pick mode) and the draft shown in preview. In memory only.
 */
@Singleton
class PlanBuilderStore @Inject constructor() {
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

    fun setPreview(draft: PlanDraft) {
        _preview.value = draft
    }

    fun clearPreview() {
        _preview.value = null
    }

    companion object {
        const val MAX_NAME = 80
        const val MAX_EXERCISES = 30
    }
}

/** Validates against the draft schema's limits. */
fun CustomPlan.errors(): Set<CustomPlanError> = buildSet {
    if (name.isBlank()) add(CustomPlanError.NAME)
    if (days.isEmpty()) add(CustomPlanError.NO_DAYS)
    days.values.forEach { d ->
        if (d.name.isBlank()) add(CustomPlanError.NAME)
        if (d.exercises.isEmpty()) add(CustomPlanError.EMPTY_DAY)
        d.exercises.forEach { e ->
            if (e.sets !in 1..20) add(CustomPlanError.BAD_SETS)
            if (e.exercise.trackingType.usesDuration) {
                if (e.durationSeconds == null || e.durationSeconds !in 1..7200) add(CustomPlanError.BAD_DURATION)
            } else {
                val lo = e.repsMin
                val hi = e.repsMax
                if (lo == null || hi == null || lo !in 1..100 || hi !in 1..100 || lo > hi) add(CustomPlanError.BAD_REPS)
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
