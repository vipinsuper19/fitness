package com.vipin.fitness.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.MeasureDimension
import com.vipin.fitness.core.data.ProgressRepository
import com.vipin.fitness.core.data.ProgressSummary
import com.vipin.fitness.core.data.UnitPreference
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.designsystem.UiText
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vipin.fitness.core.common.time.today
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

enum class ChartRange(val days: Long) { WEEK(7), MONTH(30), QUARTER(90) }

/** The metrics drawn from /progress/summary. */
enum class ChartMetric { WEIGHT, BODY_FAT, WAIST, WATER, ENERGY, ACTIVE_MINUTES, WORKOUTS }

data class ChartsState(
    val today: LocalDate,
    val range: ChartRange = ChartRange.MONTH,
    val units: UnitSystem = UnitSystem.METRIC,
    val loading: Boolean = true,
    val error: UiText? = null,
    val summary: ProgressSummary? = null,
) {
    /**
     * One value per day for [metric], in display units. A null is a gap (not measured, nothing
     * logged, or a day the server left out), never drawn as zero.
     */
    fun series(metric: ChartMetric): List<Double?> = summary?.points.orEmpty().map { p ->
        when (metric) {
            ChartMetric.WEIGHT -> p.weightKg?.let { toDisplay(MeasureDimension.MASS, it, units) }
            ChartMetric.BODY_FAT -> p.bodyFatPct
            ChartMetric.WAIST -> p.waistCm?.let { toDisplay(MeasureDimension.LENGTH, it, units) }
            ChartMetric.WATER -> p.waterMl?.let { if (units == UnitSystem.METRIC) it.toDouble() else UnitConversion.mlToFlOz(it.toDouble()) }
            ChartMetric.ENERGY -> p.energyKcal
            ChartMetric.ACTIVE_MINUTES -> p.activeMinutes?.toDouble()
            ChartMetric.WORKOUTS -> p.workoutsCompleted?.toDouble()
        }
    }
}

/** Progress charts for the chosen range ending today. */
@HiltViewModel
class ChartsViewModel @Inject constructor(
    private val repo: ProgressRepository,
    units: UnitPreference,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ChartsState(today = clock.today()))
    val state: StateFlow<ChartsState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { units.units.collect { u -> _state.update { it.copy(units = u) } } }
        load()
    }

    fun setRange(range: ChartRange) {
        if (range == _state.value.range) return
        _state.update { it.copy(range = range) }
        load()
    }

    /** Called when the screen resumes: after midnight the range must end on the new today. */
    fun onResume() {
        if (clock.today() != _state.value.today) load()
    }

    fun load() {
        val today = clock.today()
        val range = _state.value.range
        val from = today.minusDays(range.days - 1)
        loadJob?.cancel()
        _state.update { it.copy(today = today, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            when (val r = repo.summary(from, today)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, summary = r.value) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }
}

/**
 * Splits a daily series into runs of consecutive days with data. Each run is drawn as one line;
 * a null breaks the line (a gap), and a zero is a real value.
 */
internal fun chartSegments(values: List<Double?>): List<List<Pair<Int, Double>>> {
    val out = mutableListOf<List<Pair<Int, Double>>>()
    var run = mutableListOf<Pair<Int, Double>>()
    values.forEachIndexed { i, v ->
        if (v == null) {
            if (run.isNotEmpty()) out += run
            run = mutableListOf()
        } else {
            run += i to v
        }
    }
    if (run.isNotEmpty()) out += run
    return out
}
