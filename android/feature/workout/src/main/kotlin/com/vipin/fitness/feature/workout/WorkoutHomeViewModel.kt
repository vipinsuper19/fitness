package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.time.today
import com.vipin.fitness.core.data.session.WorkoutSession
import com.vipin.fitness.core.data.session.WorkoutSessionRepository
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
    /** A session in progress or paused: the tab offers Resume instead of Start. */
    val unfinishedSession: WorkoutSession? = null,
    /** Workouts the server rejected for good: shown until dismissed. */
    val flagged: List<WorkoutSession> = emptyList(),
    val starting: Boolean = false,
    /** One-shot: open the active session screen, then call [WorkoutHomeViewModel.onSessionOpened]. */
    val openSession: Boolean = false,
) {
    val hasNoPlan get() = !loading && error == null && plan == null
    val selected get() = week.firstOrNull { it.day == selectedDay }
}

/** My Plan: this week (Mon–Sun) of the active plan, today selected. */
@HiltViewModel
class WorkoutHomeViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val clock: Clock,
    private val sessions: WorkoutSessionRepository,
) : ViewModel() {
    /** The date the week was built for; a new day (app left open overnight) rebuilds it. */
    private var shownDate: LocalDate = clock.today()
    private val _state = MutableStateFlow(WorkoutHomeState(selectedDay = shownDate.dayOfWeek))
    val state: StateFlow<WorkoutHomeState> = _state.asStateFlow()

    init {
        // Activation elsewhere (preview screen) updates the repository; follow it.
        viewModelScope.launch { repo.activePlan.filterNotNull().collect { show(it) } }
        viewModelScope.launch { sessions.unfinished.collect { u -> _state.update { it.copy(unfinishedSession = u) } } }
        viewModelScope.launch { sessions.flagged.collect { f -> _state.update { it.copy(flagged = f) } } }
        refresh()
    }

    /** Screen resumed: the date may have changed since the week was built. */
    fun onResume() = refresh()

    fun refresh() {
        rebuildWeekIfNewDay()
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

    private fun rebuildWeekIfNewDay() {
        val now = clock.today()
        if (now == shownDate) return
        val old = shownDate
        shownDate = now
        _state.update {
            it.copy(
                week = if (it.week.isEmpty()) it.week else week(it.plan),
                // Follow "today" unless the user picked another day.
                selectedDay = if (it.selectedDay == old.dayOfWeek) now.dayOfWeek else it.selectedDay,
            )
        }
    }

    /** Start the selected day's workout, or resume the unfinished session (one at a time). */
    fun startWorkout() {
        val s = _state.value
        if (s.starting) return
        if (s.unfinishedSession != null) {
            _state.update { it.copy(openSession = true) }
            return
        }
        val plan = s.plan ?: return
        val workout = s.selected?.workout ?: return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            // Names and tracking types; offline and uncached, the session falls back to the plan's targets.
            val exercises = workout.exercises.map { it.exerciseId }.distinct()
                .mapNotNull { id -> (repo.exercise(id) as? ApiResult.Success)?.value }
                .associateBy { it.id }
            sessions.start(plan, workout, exercises)
            _state.update { it.copy(starting = false, openSession = true) }
        }
    }

    fun dismissFlag(localId: String) {
        viewModelScope.launch { sessions.dismissFlag(localId) }
    }

    fun onSessionOpened() = _state.update { it.copy(openSession = false) }

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
        val t = shownDate
        val monday = t.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return DayOfWeek.entries.map { d ->
            val date = monday.plusDays((d.value - 1).toLong())
            DayCell(d, date, plan?.workoutOn(d), date == t)
        }
    }
}
