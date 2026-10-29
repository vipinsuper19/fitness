package com.vipin.fitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.NutritionSummary
import com.vipin.fitness.core.data.NutritionSummaryRepository
import com.vipin.fitness.core.data.ProfileZone
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class SummaryError { OFFLINE, FAILED }

data class SummaryState(
    val bucket: SummaryBucket = SummaryBucket.WEEK,
    val loading: Boolean = true,
    val summary: NutritionSummary? = null,
    val error: SummaryError? = null,
    val units: UnitSystem = UnitSystem.METRIC,
)

/** The range shown for each bucket, ending today: 14 days, 8 weeks (from a Monday), 6 months (from the 1st). */
internal fun SummaryBucket.range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
    SummaryBucket.DAY -> today.minusDays(13) to today
    SummaryBucket.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(7) to today
    SummaryBucket.MONTH -> today.withDayOfMonth(1).minusMonths(5) to today
}

/** Water in the user's units: whole ml, or US fl oz to one decimal (en-IN, HALF_UP). */
internal fun formatWater(ml: Int, units: UnitSystem): String = when (units) {
    UnitSystem.METRIC -> NutritionFormat.ml(ml)
    UnitSystem.IMPERIAL -> "${NutritionFormat.number(UnitConversion.mlToFlOz(ml.toDouble()))} fl oz"
}

internal fun formatPct(p: Double): String = "${NutritionFormat.number(p)}%"

@HiltViewModel
class NutritionSummaryViewModel @Inject constructor(
    private val repo: NutritionSummaryRepository,
    private val profileZone: ProfileZone,
    private val clock: Clock,
    units: UnitPreference,
) : ViewModel() {
    private val _state = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = _state.asStateFlow()

    /** Bumped per load so a slow response for an old bucket is dropped. */
    private var generation = 0

    init {
        viewModelScope.launch { units.units.collect { u -> _state.update { it.copy(units = u) } } }
        load()
    }

    fun select(bucket: SummaryBucket) {
        if (bucket == _state.value.bucket) return
        _state.update { it.copy(bucket = bucket) }
        load()
    }

    fun load() {
        val gen = ++generation
        val bucket = _state.value.bucket
        _state.update { it.copy(loading = true, error = null, summary = null) }
        viewModelScope.launch {
            val (from, to) = bucket.range(clock.today(profileZone.zone()))
            val r = repo.summary(from, to, bucket)
            if (gen != generation) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(loading = false, summary = r.value)
                    is ApiResult.Failure -> it.copy(loading = false, error = if (r.error == ApiError.Network) SummaryError.OFFLINE else SummaryError.FAILED)
                }
            }
        }
    }
}
