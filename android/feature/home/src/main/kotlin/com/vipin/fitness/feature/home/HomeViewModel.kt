package com.vipin.fitness.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.DailyNutrition
import com.vipin.fitness.core.data.GoalProgress
import com.vipin.fitness.core.data.GoalProgressService
import com.vipin.fitness.core.data.HomeRepository
import com.vipin.fitness.core.data.ProgressRepository
import com.vipin.fitness.core.data.UnitPreference
import com.vipin.fitness.core.data.XpBalance
import com.vipin.fitness.core.data.history.SessionChanges
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.WorkoutPlan
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.common.time.today
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** One dashboard card's state. Each slot loads and fails on its own. */
sealed interface Slot<out T> {
    data object Loading : Slot<Nothing>
    data class Failed(val message: String) : Slot<Nothing>
    data class Ready<T>(val value: T) : Slot<T>
}

/** Today's plan day: [workout] null means a rest day; [plan] null means no active plan. */
data class PlanDay(val plan: WorkoutPlan?, val workout: PlannedWorkout?) {
    /** "Week N of M" / "Plan finished" from the plan response; null hides it. */
    val week: PlanWeek? get() = plan?.let { p ->
        val week = p.currentWeek
        when {
            p.planEnded == true -> PlanWeek.Finished
            week != null -> PlanWeek.Current(week, p.weeks)
            else -> null
        }
    }
}

sealed interface PlanWeek {
    data object Finished : PlanWeek
    data class Current(val week: Int, val of: Int) : PlanWeek
}

/** Streak from /progress/summary, plus whether a workout is already done today. */
data class Streak(val days: Int, val workoutsToday: Int, val xpToday: Int)

data class HomeState(
    val today: LocalDate,
    val units: UnitSystem = UnitSystem.METRIC,
    val plan: Slot<PlanDay> = Slot.Loading,
    val nutrition: Slot<DailyNutrition> = Slot.Loading,
    val streak: Slot<Streak> = Slot.Loading,
    val xp: Slot<XpBalance> = Slot.Loading,
    /** Active goals with progress (empty = no goals). */
    val goals: Slot<List<GoalProgress>> = Slot.Loading,
)

/** Home dashboard: today's plan day, today's nutrition and water, streak and XP. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val home: HomeRepository,
    private val workouts: WorkoutRepository,
    private val progress: ProgressRepository,
    units: UnitPreference,
    private val clock: Clock,
    private val goals: GoalProgressService,
    sessionChanges: SessionChanges = SessionChanges(),
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState(today = clock.today()))
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private val jobs = mutableListOf<Job>()
    private var lastRefreshAt = Long.MIN_VALUE / 2

    init {
        viewModelScope.launch { units.units.collect { u -> _state.update { it.copy(units = u) } } }
        refresh()
        // A past workout was corrected or discarded: XP (and the streak) changed on the server.
        viewModelScope.launch { sessionChanges.events.collect { refresh() } }
    }

    /** Called when the screen resumes: totals may have changed in other tabs, and the day may have rolled over. */
    fun onResume() {
        // The first resume follows the initial load; skip a duplicate fetch within a few seconds.
        if (clock.millis() - lastRefreshAt < RESUME_DEBOUNCE_MS && clock.today() == _state.value.today) return
        refresh()
    }


    /** Reloads every slot for today (read from the clock now). A slot keeps its last value while reloading. */
    fun refresh() {
        lastRefreshAt = clock.millis()
        val today = clock.today()
        val dayChanged = today != _state.value.today
        jobs.forEach { it.cancel() }
        jobs.clear()
        _state.update {
            if (dayChanged) HomeState(today = today, units = it.units) else it.copy(today = today)
        }
        jobs += viewModelScope.launch {
            val r = workouts.refreshActivePlan()
            _state.update { s ->
                if (s.today != today) s
                else s.copy(plan = r.toSlot { a -> (a as? ActivePlan.Some)?.plan.let { PlanDay(it, it?.workoutOn(today.dayOfWeek)) } })
            }
        }
        jobs += viewModelScope.launch {
            val r = home.dailyNutrition(today)
            _state.update { s -> if (s.today != today) s else s.copy(nutrition = r.toSlot { it }) }
        }
        jobs += viewModelScope.launch {
            val r = progress.summary(today, today)
            _state.update { s ->
                if (s.today != today) s
                else s.copy(streak = r.toSlot { sum ->
                    val p = sum.points.firstOrNull { it.date == today }
                    Streak(sum.streakDays, p?.workoutsCompleted ?: 0, p?.xpEarned ?: 0)
                })
            }
        }
        jobs += viewModelScope.launch {
            val r = home.xpBalance()
            _state.update { s -> if (s.today != today) s else s.copy(xp = r.toSlot { it }) }
        }
        jobs += viewModelScope.launch {
            val r = goals.load()
            _state.update { s -> if (s.today != today) s else s.copy(goals = r.toSlot { it }) }
        }
    }

    private fun <T, R> ApiResult<T>.toSlot(map: (T) -> R): Slot<R> = when (this) {
        is ApiResult.Success -> Slot.Ready(map(value))
        is ApiResult.Failure -> Slot.Failed(error.userMessage())
    }

    private companion object {
        const val RESUME_DEBOUNCE_MS = 5_000L
    }
}
