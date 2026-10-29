package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.UnitPreference
import com.vipin.fitness.core.data.workout.isPlanArchived
import com.vipin.fitness.core.data.progression.ApplyOutcome
import com.vipin.fitness.core.data.progression.ProgressionData
import com.vipin.fitness.core.data.progression.ProgressionRepository
import com.vipin.fitness.core.data.progression.SuggestionRow
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.DEFAULT_RETRY_AFTER_MS
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

enum class SuggestionDecision { ACCEPT, SKIP }

enum class ProgressionLoadError { OFFLINE, ARCHIVED, RATE_LIMITED, FAILED }

/** Result of the last Apply, shown under the button. */
sealed interface ApplyStatus {
    data class Applied(val newVersion: Int) : ApplyStatus
    /** Someone else changed the plan; [currentVersion] reloaded, the user re-confirms. */
    data class Conflict(val currentVersion: Int?) : ApplyStatus
    data object Archived : ApplyStatus
    /** The key was used for a different body; suggestions reloaded, a new key next time. */
    data object KeyReused : ApplyStatus
    data object InProgress : ApplyStatus
    data class RateLimited(val retryAfterMs: Long?) : ApplyStatus
    data object Offline : ApplyStatus
    data object Failed : ApplyStatus
}

data class ProgressionUiState(
    val planId: String? = null,
    val loading: Boolean = false,
    val loadError: ProgressionLoadError? = null,
    /** With RATE_LIMITED: the GET's Retry-After, in ms; null if absent. */
    val loadRetryAfterMs: Long? = null,
    val data: ProgressionData? = null,
    val decisions: Map<String, SuggestionDecision> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val units: UnitSystem = UnitSystem.METRIC,
    val applying: Boolean = false,
    val status: ApplyStatus? = null,
) {
    val rows: List<SuggestionRow> get() = data?.rows.orEmpty()
    /** Accepted entries that can be written (hold / unknown actions never are). */
    val acceptedKeys: Set<String>
        get() = rows.filter { it.suggestion.actionable && decisions[it.suggestion.key] == SuggestionDecision.ACCEPT }
            .map { it.suggestion.key }.toSet()
    val canApply: Boolean get() = !applying && !loading && data != null && status != ApplyStatus.Archived && acceptedKeys.isNotEmpty()
}

/**
 * Plan screen's Suggestions section. Nothing is applied until the user accepts entries and taps
 * Apply, which sends one PATCH with every accepted change. The Idempotency-Key is kept for a retry
 * of the same (version, accepted set) and replaced when either changes.
 */
@HiltViewModel
class ProgressionSuggestionsViewModel @Inject constructor(
    private val repo: ProgressionRepository,
    private val workouts: WorkoutRepository,
    units: UnitPreference,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ProgressionUiState())
    val state: StateFlow<ProgressionUiState> = _state.asStateFlow()

    /** Test seam: Idempotency-Key factory. */
    internal var newKey: () -> String = { UUID.randomUUID().toString() }
    private var loadGen = 0
    private var pendingKey: String? = null
    private var pendingFor: Pair<Int, Set<String>>? = null
    private var rateLimitedUntilMs = 0L

    init {
        viewModelScope.launch { units.units.collect { u -> _state.update { it.copy(units = u) } } }
        // Another user signed in: nothing of the previous one may show or apply.
        viewModelScope.launch {
            repo.userGeneration.drop(1).collect {
                loadGen++
                dropKey()
                rateLimitedUntilMs = 0
                _state.value = ProgressionUiState(units = _state.value.units)
            }
        }
    }

    /** Shows [planId]'s suggestions; a different plan starts clean. */
    fun load(planId: String) {
        if (planId != _state.value.planId) {
            dropKey()
            _state.update { ProgressionUiState(planId = planId, units = it.units) }
        }
        reload()
    }

    fun retry() = reload()

    fun accept(key: String) = decide(key, SuggestionDecision.ACCEPT)
    fun skip(key: String) = decide(key, SuggestionDecision.SKIP)

    private fun decide(key: String, d: SuggestionDecision) {
        val s = _state.value
        val row = s.rows.firstOrNull { it.suggestion.key == key } ?: return
        if (d == SuggestionDecision.ACCEPT && !row.suggestion.actionable) return
        _state.update { it.copy(decisions = it.decisions + (key to d)) }
    }

    fun apply() {
        val s = _state.value
        if (!s.canApply) return
        val data = s.data ?: return
        val accepted = s.acceptedKeys
        val now = clock.millis()
        if (now < rateLimitedUntilMs) {
            _state.update { it.copy(status = ApplyStatus.RateLimited(rateLimitedUntilMs - now)) }
            return
        }
        val signature = data.planVersion to accepted
        if (pendingKey == null || pendingFor != signature) {
            pendingKey = newKey()
            pendingFor = signature
        }
        val key = pendingKey!!
        val user = repo.userGeneration.value
        _state.update { it.copy(applying = true, status = null) }
        viewModelScope.launch {
            val outcome = repo.apply(data, accepted, key)
            if (user != repo.userGeneration.value) return@launch
            _state.update { it.copy(applying = false) }
            when (outcome) {
                is ApplyOutcome.Applied -> {
                    dropKey()
                    _state.update { it.copy(status = ApplyStatus.Applied(outcome.newVersion), decisions = emptyMap()) }
                    reload()
                }
                is ApplyOutcome.VersionConflict -> {
                    dropKey()
                    // Nothing was written: show the newer version and make the user confirm again.
                    _state.update { it.copy(status = ApplyStatus.Conflict(outcome.currentVersion), decisions = emptyMap()) }
                    reload()
                }
                ApplyOutcome.PlanArchived -> {
                    dropKey()
                    _state.update { it.copy(status = ApplyStatus.Archived) }
                }
                ApplyOutcome.KeyReused -> {
                    dropKey()
                    _state.update { it.copy(status = ApplyStatus.KeyReused) }
                    reload()
                }
                // Same body may still succeed: keep the key so a retry replays it.
                ApplyOutcome.InProgress -> _state.update { it.copy(status = ApplyStatus.InProgress) }
                is ApplyOutcome.RateLimited -> {
                    rateLimitedUntilMs = clock.millis() + (outcome.retryAfterMs ?: DEFAULT_RETRY_AFTER_MS)
                    _state.update { it.copy(status = ApplyStatus.RateLimited(outcome.retryAfterMs)) }
                }
                ApplyOutcome.Offline -> _state.update { it.copy(status = ApplyStatus.Offline) }
                ApplyOutcome.NothingToApply -> Unit
                is ApplyOutcome.Failed -> {
                    // A 4xx rejected this body for good; a 5xx/unknown may be retried with the same key.
                    if ((outcome.error as? ApiError.Http)?.status in 400..499) dropKey()
                    _state.update { it.copy(status = ApplyStatus.Failed) }
                }
            }
        }
    }

    private fun dropKey() {
        pendingKey = null
        pendingFor = null
    }

    private fun reload() {
        val planId = _state.value.planId ?: return
        val gen = ++loadGen
        val user = repo.userGeneration.value
        _state.update { it.copy(loading = true, loadError = null, loadRetryAfterMs = null) }
        viewModelScope.launch {
            val r = repo.load(planId)
            if (gen != loadGen || user != repo.userGeneration.value) return@launch
            when (r) {
                is ApiResult.Success -> {
                    val data = r.value
                    _state.update { st ->
                        // Decisions survive only a reload of the same version, for rows still present.
                        val keep = if (st.data?.planVersion == data.planVersion) {
                            val keys = data.rows.map { it.suggestion.key }.toSet()
                            st.decisions.filterKeys { it in keys }
                        } else emptyMap()
                        st.copy(loading = false, loadError = null, data = data, decisions = keep)
                    }
                    if (data.planVersion != pendingFor?.first) dropKey()
                    val ids = data.rows.flatMap { listOf(it.suggestion.exerciseId) + it.suggestion.harderVariations }
                    val names = workouts.namesFor(ids)
                    if (gen != loadGen || user != repo.userGeneration.value) return@launch
                    _state.update { it.copy(names = it.names + names) }
                }
                is ApiResult.Failure -> _state.update {
                    val e = r.error
                    val err = when {
                        e.isPlanArchived() -> ProgressionLoadError.ARCHIVED
                        e is ApiError.Http && e.status == 429 -> ProgressionLoadError.RATE_LIMITED
                        e == ApiError.Network -> ProgressionLoadError.OFFLINE
                        else -> ProgressionLoadError.FAILED
                    }
                    it.copy(loading = false, loadError = err, loadRetryAfterMs = (e as? ApiError.Http)?.retryAfterMs, data = if (err == ProgressionLoadError.ARCHIVED) null else it.data)
                }
            }
        }
    }
}
