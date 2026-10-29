package com.vipin.fitness.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.ExerciseProgress
import com.vipin.fitness.core.data.ExerciseProgressSummary
import com.vipin.fitness.core.data.ProfileZone
import com.vipin.fitness.core.data.StrengthProgressRepository
import com.vipin.fitness.core.data.SummaryBucket
import com.vipin.fitness.core.data.UnitPreference
import com.vipin.fitness.core.data.today
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class StrengthError { OFFLINE, FAILED, NOT_FOUND }

internal fun ApiError.toStrengthError() = when {
    this == ApiError.Network -> StrengthError.OFFLINE
    this is ApiError.Http && status == 404 -> StrengthError.NOT_FOUND
    else -> StrengthError.FAILED
}

/** Ranges stay within the API's 366-day limit for every bucket; all end today in the profile zone. */
internal object StrengthRanges {
    const val LIST_DAYS = 90L
    fun list(today: LocalDate) = today.minusDays(LIST_DAYS - 1) to today
    fun detail(today: LocalDate, bucket: SummaryBucket): Pair<LocalDate, LocalDate> = when (bucket) {
        SummaryBucket.DAY -> today.minusDays(29) to today
        SummaryBucket.WEEK -> today.minusWeeks(26).plusDays(1) to today
        SummaryBucket.MONTH -> today.minusDays(365) to today
    }
}

data class StrengthDetailState(
    val exerciseId: String,
    val bucket: SummaryBucket = SummaryBucket.WEEK,
    val loading: Boolean = true,
    val progress: ExerciseProgress? = null,
    val error: StrengthError? = null,
)

data class StrengthState(
    val loading: Boolean = true,
    val items: List<ExerciseProgressSummary> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val error: StrengthError? = null,
    val moreError: StrengthError? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    val zone: ZoneId = APP_ZONE,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val detail: StrengthDetailState? = null,
) {
    val empty get() = !loading && error == null && items.isEmpty()
}

/** Strength progress: exercises performed in the last 90 days (all-time PRs), and one exercise's periods. */
@HiltViewModel
class StrengthViewModel @Inject constructor(
    private val repo: StrengthProgressRepository,
    units: UnitPreference,
    private val profileZone: ProfileZone,
    private val clock: Clock,
) : ViewModel() {
    private var generation = 0
    private var detailGeneration = 0
    private val _state = MutableStateFlow(StrengthState())
    val state: StateFlow<StrengthState> = _state.asStateFlow()

    init {
        viewModelScope.launch { units.units.collect { u -> _state.update { it.copy(units = u) } } }
        refresh()
    }

    fun refresh() {
        val gen = ++generation
        _state.update { it.copy(loading = true, error = null, moreError = null, items = emptyList(), nextCursor = null, loadingMore = false) }
        viewModelScope.launch {
            val zone = profileZone.zone()
            val (from, to) = StrengthRanges.list(clock.today(zone))
            if (gen != generation) return@launch
            _state.update { it.copy(zone = zone, from = from, to = to) }
            val r = repo.exercises(from, to, null)
            if (gen != generation) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(loading = false, items = r.value.items, nextCursor = r.value.nextCursor)
                    is ApiResult.Failure -> it.copy(loading = false, error = r.error.toStrengthError())
                }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        val from = s.from ?: return
        val to = s.to ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreError = null) }
        val gen = generation
        viewModelScope.launch {
            val r = repo.exercises(from, to, cursor)
            if (gen != generation) return@launch
            when (r) {
                is ApiResult.Success -> _state.update {
                    val seen = it.items.mapTo(HashSet()) { e -> e.exerciseId }
                    it.copy(loadingMore = false, items = it.items + r.value.items.filter { e -> e.exerciseId !in seen }, nextCursor = r.value.nextCursor)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, moreError = r.error.toStrengthError()) }
            }
        }
    }

    /** Opening an exercise always starts on the Week bucket. */
    fun open(exerciseId: String) {
        _state.update { it.copy(detail = StrengthDetailState(exerciseId, bucket = SummaryBucket.WEEK)) }
        loadDetail()
    }

    fun setBucket(bucket: SummaryBucket) {
        val d = _state.value.detail ?: return
        if (d.bucket == bucket) return
        _state.update { it.copy(detail = d.copy(bucket = bucket)) }
        loadDetail()
    }

    fun closeDetail() {
        detailGeneration++
        _state.update { it.copy(detail = null) }
    }

    fun retryDetail() = loadDetail()

    private fun loadDetail() {
        val d = _state.value.detail ?: return
        val gen = ++detailGeneration
        _state.update { it.copy(detail = d.copy(loading = true, error = null, progress = null)) }
        viewModelScope.launch {
            val zone = profileZone.zone()
            val (from, to) = StrengthRanges.detail(clock.today(zone), d.bucket)
            val r = repo.exercise(d.exerciseId, from, to, d.bucket)
            if (gen != detailGeneration) return@launch
            _state.update {
                val cur = it.detail ?: return@update it
                it.copy(
                    zone = zone,
                    detail = when (r) {
                        is ApiResult.Success -> cur.copy(loading = false, progress = r.value)
                        is ApiResult.Failure -> cur.copy(loading = false, error = r.error.toStrengthError())
                    },
                )
            }
        }
    }
}
