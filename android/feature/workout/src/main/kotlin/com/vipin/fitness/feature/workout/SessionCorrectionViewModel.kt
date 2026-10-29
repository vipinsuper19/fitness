package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.ProfileZone
import com.vipin.fitness.core.data.achievements.Badge
import com.vipin.fitness.core.data.history.CorrectionErrors
import com.vipin.fitness.core.data.history.CorrectionWindow
import com.vipin.fitness.core.data.history.PastSession
import com.vipin.fitness.core.data.history.PastSet
import com.vipin.fitness.core.data.history.SessionCorrectionRepository
import com.vipin.fitness.core.data.history.SessionCorrectionResult
import com.vipin.fitness.core.data.today
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
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

enum class CorrectionError { WINDOW_CLOSED, SESSION_EMPTY, INVALID_TIME, INVALID, OFFLINE, FAILED }

/** The last correction's effect, shown until the next action. */
data class CorrectionOutcome(val xpDelta: Int, val newBadges: List<Badge>)

data class CorrectionState(
    /** The session as the server returned it after the last correction (null = use the loaded one). */
    val session: PastSession? = null,
    val zone: ZoneId = APP_ZONE,
    /** Cleared by a 409 CORRECTION_WINDOW_CLOSED even if the local check said open. */
    val windowClosedByServer: Boolean = false,
    val busy: Boolean = false,
    val outcome: CorrectionOutcome? = null,
    val error: CorrectionError? = null,
    val discarded: Boolean = false,
)

/**
 * Corrections and discard for one completed session (#63). Edits are offered only while its
 * completedAt local date in the profile zone is today or yesterday; the server has the last word.
 */
@HiltViewModel(assistedFactory = SessionCorrectionViewModel.Factory::class)
class SessionCorrectionViewModel @AssistedInject constructor(
    private val repo: SessionCorrectionRepository,
    private val profileZone: ProfileZone,
    private val clock: Clock,
    @Assisted private val sessionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(CorrectionState())
    val state: StateFlow<CorrectionState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val z = profileZone.zone()
            _state.update { it.copy(zone = z) }
        }
    }

    /** Whether [session] (the shown one) can still be corrected. */
    fun canCorrect(session: PastSession?): Boolean {
        val s = _state.value
        return session != null && !s.windowClosedByServer && !s.discarded &&
            CorrectionWindow.isOpen(session.completedAt, s.zone, clock.today(s.zone))
    }

    fun saveSet(set: PastSet) = run { repo.upsertSet(sessionId, set) }

    /**
     * 404 SET_NOT_FOUND means the set is already gone (e.g. an earlier delete whose response was
     * lost), so it counts as success: the set is dropped from the shown session ([current] until
     * a correction has returned one), with no XP change.
     */
    fun deleteSet(clientSetId: String, current: PastSession? = null) = run(
        onError = { e ->
            if (e is ApiError.Http && e.status == 404 && e.code == CorrectionErrors.SET_NOT_FOUND) {
                val shown = _state.value.session ?: current
                shown?.let { s -> s.copy(sets = s.sets.filter { it.clientSetId != clientSetId }) }
            } else null
        },
    ) { repo.deleteSet(sessionId, clientSetId) }

    /** [completedAt] must move earlier on the same local date and stay after the start. */
    fun correctCompletion(current: PastSession, completedAt: Instant?, perceivedDifficulty: Int?, notes: String?) {
        val done = current.completedAt
        if (completedAt != null && (done == null || !CorrectionWindow.validCompletedAt(completedAt, done, current.startedAt, _state.value.zone))) {
            _state.update { it.copy(error = CorrectionError.INVALID_TIME, outcome = null) }
            return
        }
        run { repo.correctCompletion(sessionId, completedAt, perceivedDifficulty, notes) }
    }

    fun discard() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, outcome = null) }
        viewModelScope.launch {
            when (val r = repo.discard(sessionId)) {
                is ApiResult.Success -> _state.update { it.copy(busy = false, discarded = true) }
                is ApiResult.Failure -> _state.update { it.copy(busy = false, error = r.error.toCorrectionError()) }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(outcome = null, error = null) }

    private fun run(
        onError: (ApiError) -> PastSession? = { null },
        call: suspend () -> ApiResult<SessionCorrectionResult>,
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, outcome = null) }
        viewModelScope.launch {
            val r = call()
            val recovered = (r as? ApiResult.Failure)?.let { onError(it.error) }
            if (recovered != null) {
                _state.update { it.copy(busy = false, session = recovered, outcome = CorrectionOutcome(0, emptyList())) }
                return@launch
            }
            when (r) {
                is ApiResult.Success -> _state.update {
                    it.copy(busy = false, session = r.value.session, outcome = CorrectionOutcome(r.value.xpDelta, r.value.newBadges))
                }
                is ApiResult.Failure -> {
                    val e = r.error.toCorrectionError()
                    _state.update { it.copy(busy = false, error = e, windowClosedByServer = it.windowClosedByServer || e == CorrectionError.WINDOW_CLOSED) }
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(sessionId: String): SessionCorrectionViewModel
    }
}

internal fun ApiError.toCorrectionError() = when {
    this == ApiError.Network -> CorrectionError.OFFLINE
    this is ApiError.Http && code == CorrectionErrors.WINDOW_CLOSED -> CorrectionError.WINDOW_CLOSED
    this is ApiError.Http && code == CorrectionErrors.SESSION_EMPTY -> CorrectionError.SESSION_EMPTY
    this is ApiError.Http && status == 400 -> CorrectionError.INVALID
    else -> CorrectionError.FAILED
}
