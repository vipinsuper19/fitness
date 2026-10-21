package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.data.workout.Equipment
import com.vipin.fitness.core.data.workout.Exercise
import com.vipin.fitness.core.data.workout.ExerciseFilter
import com.vipin.fitness.core.data.workout.MuscleGroup
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class LibraryState(
    val filter: ExerciseFilter = ExerciseFilter(),
    val items: List<Exercise> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val nextCursor: String? = null,
    /** Pick mode: tapping an exercise adds it to this day of the custom plan. */
    val pickDay: DayOfWeek? = null,
    val picked: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class ExerciseLibraryViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val store: PlanBuilderStore,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val chips = MutableStateFlow<Pair<MuscleGroup?, Equipment?>>(null to null)
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // Typing is debounced; chip taps apply at once (the debounce only delays query changes).
            combine(query.debounce(DEBOUNCE_MS).map { it.trim() }.distinctUntilChanged(), chips) { q, (m, e) ->
                ExerciseFilter(q, m, e)
            }.distinctUntilChanged().collect { load(it) }
        }
    }

    fun setPickDay(day: DayOfWeek?) = _state.update { it.copy(pickDay = day) }

    fun onQuery(q: String) {
        val text = q.take(100)
        query.value = text
        _state.update { it.copy(filter = it.filter.copy(query = text)) }
    }

    /** Tapping the selected chip again clears it. */
    fun onMuscle(m: MuscleGroup?) {
        chips.update { (cur, e) -> (if (cur == m) null else m) to e }
        _state.update { it.copy(filter = it.filter.copy(muscle = chips.value.first)) }
    }

    fun onEquipment(e: Equipment?) {
        chips.update { (m, cur) -> m to (if (cur == e) null else e) }
        _state.update { it.copy(filter = it.filter.copy(equipment = chips.value.second)) }
    }

    fun retry() = load(currentFilter())

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true, error = null) }
        val filter = currentFilter()
        loadJob = viewModelScope.launch {
            when (val r = repo.exercises(filter, cursor)) {
                is ApiResult.Success -> _state.update {
                    val seen = it.items.mapTo(HashSet()) { e -> e.id }
                    it.copy(items = it.items + r.value.items.filter { e -> e.id !in seen }, nextCursor = r.value.nextCursor, loadingMore = false)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, error = r.error.userMessage()) }
            }
        }
    }

    /** Pick mode only. */
    fun pick(exercise: Exercise) {
        val day = _state.value.pickDay ?: return
        if (_state.value.picked) return
        store.addExercise(day, exercise, day.fullName())
        _state.update { it.copy(picked = true) }
    }

    private fun currentFilter() = ExerciseFilter(query.value.trim(), chips.value.first, chips.value.second)

    private fun load(filter: ExerciseFilter) {
        loadJob?.cancel() // a newer filter wins; stale pages are dropped
        _state.update { it.copy(loading = true, loadingMore = false, error = null) }
        loadJob = viewModelScope.launch {
            when (val r = repo.exercises(filter)) {
                is ApiResult.Success -> _state.update { it.copy(items = r.value.items, nextCursor = r.value.nextCursor, loading = false) }
                is ApiResult.Failure -> _state.update { it.copy(items = emptyList(), nextCursor = null, loading = false, error = r.error.userMessage()) }
            }
        }
    }

    internal companion object {
        const val DEBOUNCE_MS = 300L
    }
}
