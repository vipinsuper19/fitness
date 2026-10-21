package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.data.workout.Exercise
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExerciseDetailState(val loading: Boolean = true, val exercise: Exercise? = null, val error: String? = null)

@HiltViewModel(assistedFactory = ExerciseDetailViewModel.Factory::class)
class ExerciseDetailViewModel @AssistedInject constructor(
    private val repo: WorkoutRepository,
    @Assisted private val exerciseId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ExerciseDetailState())
    val state: StateFlow<ExerciseDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = ExerciseDetailState(loading = true)
        viewModelScope.launch {
            _state.value = when (val r = repo.exercise(exerciseId)) {
                is ApiResult.Success -> ExerciseDetailState(loading = false, exercise = r.value)
                is ApiResult.Failure -> ExerciseDetailState(loading = false, error = r.error.userMessage())
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(exerciseId: String): ExerciseDetailViewModel
    }
}
