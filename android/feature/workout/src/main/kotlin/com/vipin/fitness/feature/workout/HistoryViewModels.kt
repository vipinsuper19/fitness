package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.history.PastSession
import com.vipin.fitness.core.data.history.WorkoutHistoryRepository
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HistoryError { OFFLINE, FAILED, NOT_FOUND }

internal fun ApiError.toHistoryError() = when {
    this == ApiError.Network -> HistoryError.OFFLINE
    this is ApiError.Http && status == 404 -> HistoryError.NOT_FOUND
    else -> HistoryError.FAILED
}

data class HistoryState(
    val loading: Boolean = true,
    val items: List<PastSession> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    /** First page failed: full-screen error with Retry. */
    val error: HistoryError? = null,
    /** A later page failed: the list stays, with a retry row. */
    val moreError: HistoryError? = null,
) {
    val empty get() = !loading && error == null && items.isEmpty()
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: WorkoutHistoryRepository,
    displaySource: HistoryDisplaySource,
) : ViewModel() {
    val display: StateFlow<HistoryDisplay> = displaySource.display.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryDisplay())
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = HistoryState(loading = true)
        viewModelScope.launch {
            _state.value = when (val r = repo.history(null)) {
                is ApiResult.Success -> HistoryState(loading = false, items = r.value.items, nextCursor = r.value.nextCursor)
                is ApiResult.Failure -> HistoryState(loading = false, error = r.error.toHistoryError())
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreError = null) }
        viewModelScope.launch {
            when (val r = repo.history(cursor)) {
                is ApiResult.Success -> _state.update {
                    val seen = it.items.mapTo(HashSet()) { p -> p.id }
                    it.copy(loadingMore = false, items = it.items + r.value.items.filter { p -> p.id !in seen }, nextCursor = r.value.nextCursor)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, moreError = r.error.toHistoryError()) }
            }
        }
    }
}

data class PastSessionState(
    val loading: Boolean = true,
    val session: PastSession? = null,
    val exerciseNames: Map<String, String> = emptyMap(),
    val error: HistoryError? = null,
)

/**
 * One past session with its sets. Read-only for now: corrections and discard (#57) will add
 * actions here. A discarded session answers 404, shown as "no longer available".
 */
@HiltViewModel(assistedFactory = PastSessionViewModel.Factory::class)
class PastSessionViewModel @AssistedInject constructor(
    private val repo: WorkoutHistoryRepository,
    private val workouts: WorkoutRepository,
    displaySource: HistoryDisplaySource,
    @Assisted private val sessionId: String,
) : ViewModel() {
    val display: StateFlow<HistoryDisplay> = displaySource.display.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryDisplay())
    private val _state = MutableStateFlow(PastSessionState())
    val state: StateFlow<PastSessionState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = PastSessionState(loading = true)
        viewModelScope.launch {
            when (val r = repo.session(sessionId)) {
                is ApiResult.Success -> {
                    _state.value = PastSessionState(loading = false, session = r.value)
                    val names = workouts.namesFor(r.value.exerciseIds)
                    _state.update { it.copy(exerciseNames = names) }
                }
                is ApiResult.Failure -> _state.value = PastSessionState(loading = false, error = r.error.toHistoryError())
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(sessionId: String): PastSessionViewModel
    }
}
