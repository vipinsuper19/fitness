package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.WorkoutPlan
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class DayCell(val day: DayOfWeek, val date: LocalDate, val workout: PlannedWorkout?, val isToday: Boolean)

data class WorkoutHomeState(
    val loading: Boolean = true,
    /** Load failed and nothing is cached. */
    val error: String? = null,
    /** Refresh failed but an earlier plan is shown. */
    val stale: Boolean = false,
    val plan: WorkoutPlan? = null,
    val week: List<DayCell> = emptyList(),
    val selectedDay: DayOfWeek = DayOfWeek.MONDAY,
    val exerciseNames: Map<String, String> = emptyMap(),
) {
    val hasNoPlan get() = !loading && error == null && plan == null
    val selected get() = week.firstOrNull { it.day == selectedDay }
}

/** My Plan: this week (Mon–Sun) of the active plan, today selected. */
@HiltViewModel
class WorkoutHomeViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val clock: Clock,
) : ViewModel() {
    private val today get() = LocalDate.now(clock)
    private val _state = MutableStateFlow(WorkoutHomeState(selectedDay = LocalDate.now(clock).dayOfWeek))
    val state: StateFlow<WorkoutHomeState> = _state.asStateFlow()

    init {
        // Activation elsewhere (preview screen) updates the repository; follow it.
        viewModelScope.launch { repo.activePlan.filterNotNull().collect { show(it) } }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = it.plan == null, error = null, stale = false) }
        viewModelScope.launch {
            when (val r = repo.refreshActivePlan()) {
                is ApiResult.Success -> Unit // collected above
                is ApiResult.Failure -> _state.update {
                    if (it.plan != null) it.copy(loading = false, stale = true) else it.copy(loading = false, error = r.error.userMessage())
                }
            }
        }
    }

    fun select(day: DayOfWeek) = _state.update { it.copy(selectedDay = day) }

    private fun show(active: ActivePlan) {
        val plan = (active as? ActivePlan.Some)?.plan
        _state.update { it.copy(loading = false, error = null, stale = false, plan = plan, week = week(plan)) }
        if (plan != null) {
            viewModelScope.launch {
                val names = repo.namesFor(plan.workouts.flatMap { w -> w.exercises.map { it.exerciseId } })
                _state.update { it.copy(exerciseNames = it.exerciseNames + names) }
            }
        }
    }

    private fun week(plan: WorkoutPlan?): List<DayCell> {
        val t = today
        val monday = t.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return DayOfWeek.entries.map { d ->
            val date = monday.plusDays((d.value - 1).toLong())
            DayCell(d, date, plan?.workoutOn(d), date == t)
        }
    }
}
