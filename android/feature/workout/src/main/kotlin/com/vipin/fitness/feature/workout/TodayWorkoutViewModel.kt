package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.session.WorkoutSession
import com.vipin.fitness.core.data.session.WorkoutSessionRepository
import com.vipin.fitness.core.data.workout.Exercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.WorkoutPlan
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TodayWorkoutState(
    val unfinished: WorkoutSession? = null,
    val starting: Boolean = false,
    /** One-shot: open the active session, then call [TodayWorkoutViewModel.onSessionOpened]. */
    val openSession: Boolean = false,
)

/** Start or resume today's workout from outside the Workout tab (the Home plan card). */
@HiltViewModel
class TodayWorkoutViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val sessions: WorkoutSessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TodayWorkoutState())
    val state: StateFlow<TodayWorkoutState> = _state.asStateFlow()

    init {
        viewModelScope.launch { sessions.unfinished.collect { u -> _state.update { it.copy(unfinished = u) } } }
    }

    /** Resumes the unfinished session if there is one (one at a time), else starts [workout]. */
    fun startOrResume(plan: WorkoutPlan?, workout: PlannedWorkout?) {
        val s = _state.value
        if (s.starting) return
        if (s.unfinished != null) {
            _state.update { it.copy(openSession = true) }
            return
        }
        if (plan == null || workout == null) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            sessions.start(plan, workout, repo.exerciseDetails(workout))
            _state.update { it.copy(starting = false, openSession = true) }
        }
    }

    fun onSessionOpened() = _state.update { it.copy(openSession = false) }
}

/** Catalog details for a workout's exercises; offline and uncached ones are left out (the session falls back to plan targets). */
internal suspend fun WorkoutRepository.exerciseDetails(workout: PlannedWorkout): Map<String, Exercise> =
    workout.exercises.map { it.exerciseId }.distinct()
        .mapNotNull { id -> (exercise(id) as? ApiResult.Success)?.value }
        .associateBy { it.id }
