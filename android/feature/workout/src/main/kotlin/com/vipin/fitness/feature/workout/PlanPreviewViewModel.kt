package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlanPreviewState(
    val draft: PlanDraft? = null,
    val exerciseNames: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val error: String? = null,
    /** Saved and activated: go back to the Workout tab. */
    val done: Boolean = false,
)

/**
 * Preview, then save as version 1 and activate. Two calls: if create succeeds and activate
 * fails, the created plan id is kept (in [PlanBuilderStore.saveProgress], so it outlives this
 * ViewModel) and a retry only activates: no duplicate plan. Each call has its own
 * Idempotency-Key, reused across retries.
 */
@HiltViewModel
class PlanPreviewViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val store: PlanBuilderStore,
) : ViewModel() {
    private val _state = MutableStateFlow(PlanPreviewState(draft = store.preview.value))
    val state: StateFlow<PlanPreviewState> = _state.asStateFlow()

    internal var newKey: () -> String = ::newIdempotencyKey

    init {
        val draft = _state.value.draft
        if (draft != null) {
            viewModelScope.launch {
                val names = repo.namesFor(draft.workouts.flatMap { w -> w.exercises.map { it.exerciseId } })
                _state.update { it.copy(exerciseNames = names) }
            }
        }
    }

    fun saveAndActivate() {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.saving || s.done) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val p = store.saveProgress.value
            val planId = p.createdPlanId ?: when (val c = repo.create(draft, p.createKey ?: newKey().also { k -> store.updateSaveProgress { it.copy(createKey = k) } })) {
                is ApiResult.Success -> c.value.id.also { id -> store.updateSaveProgress { it.copy(createdPlanId = id) } }
                is ApiResult.Failure -> {
                    _state.update { it.copy(saving = false, error = c.error.userMessage()) }
                    return@launch
                }
            }
            when (val a = repo.activate(planId, store.saveProgress.value.activateKey ?: newKey().also { k -> store.updateSaveProgress { it.copy(activateKey = k) } })) {
                is ApiResult.Success -> {
                    store.clearPreview()
                    if (draft.source == PlanSource.CUSTOM) store.resetCustom()
                    _state.update { it.copy(saving = false, done = true) }
                }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, error = a.error.userMessage()) }
            }
        }
    }
}
