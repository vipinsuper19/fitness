package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.session.FinishOutcome
import com.vipin.fitness.core.data.session.SessionSet
import com.vipin.fitness.core.data.session.SessionStatus
import com.vipin.fitness.core.data.session.WorkoutSession
import com.vipin.fitness.core.data.session.WorkoutSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** Counting down after a set. Not persisted: a rest outlives neither the screen nor the process. */
data class RestTimer(val endsAt: Instant, val totalSeconds: Int) {
    fun remainingSeconds(now: Instant) = ((endsAt.toEpochMilli() - now.toEpochMilli() + 999) / 1000).coerceAtLeast(0)
}

data class ActiveSessionState(
    val loading: Boolean = true,
    val session: WorkoutSession? = null,
    val now: Instant = Instant.EPOCH,
    val rest: RestTimer? = null,
    val pendingWrites: Int = 0,
    val showFinish: Boolean = false,
    val difficulty: Int? = null,
    val notes: String = "",
    val finishError: FinishError? = null,
    /** One-shot: finished; show the summary for this session. */
    val finishedId: String? = null,
) {
    val activeSeconds get() = session?.activeSeconds(now) ?: 0
    val restRemaining get() = rest?.remainingSeconds(now) ?: 0
    val paused get() = session?.status == SessionStatus.PAUSED
}

enum class FinishError { NO_COMPLETED_SETS, NO_SESSION }

/**
 * The workout in progress. Everything is saved locally first by the repository and synced in
 * the background, so the screen never waits for the network.
 */
@HiltViewModel
class ActiveSessionViewModel @Inject constructor(
    private val sessions: WorkoutSessionRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ActiveSessionState(now = clock.instant()))
    val state: StateFlow<ActiveSessionState> = _state.asStateFlow()
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            sessions.unfinished.collect { s ->
                _state.update {
                    // Finished from here: keep showing the last state until the screen leaves.
                    if (s == null && it.finishedId != null) it else it.copy(loading = false, session = s)
                }
            }
        }
        viewModelScope.launch { sessions.pendingWrites.collect { n -> _state.update { it.copy(pendingWrites = n) } } }
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    internal fun tick() = _state.update {
        val now = clock.instant()
        it.copy(now = now, rest = it.rest?.takeIf { r -> r.remainingSeconds(now) > 0 })
    }

    fun toggleSet(set: SessionSet) {
        val s = _state.value.session ?: return
        val exercise = s.exercises.firstOrNull { e -> e.sets.any { it.clientSetId == set.clientSetId } } ?: return
        val completing = !set.completed
        viewModelScope.launch { sessions.setCompleted(set.clientSetId, completing) }
        if (completing && exercise.restSeconds > 0) {
            val now = clock.instant()
            _state.update { it.copy(now = now, rest = RestTimer(now.plusSeconds(exercise.restSeconds.toLong()), exercise.restSeconds)) }
        }
    }

    fun editSet(set: SessionSet, reps: Int?, weightKg: Double?, durationSeconds: Int?) {
        viewModelScope.launch { sessions.editSet(set.clientSetId, reps, weightKg, durationSeconds) }
    }

    fun skipRest() = _state.update { it.copy(rest = null) }

    fun addRest(seconds: Int) = _state.update { s ->
        s.rest?.let { s.copy(rest = it.copy(endsAt = it.endsAt.plusSeconds(seconds.toLong()), totalSeconds = it.totalSeconds + seconds)) } ?: s
    }

    fun togglePause() {
        val paused = _state.value.paused
        viewModelScope.launch { if (paused) sessions.resume() else sessions.pause() }
        if (!paused) _state.update { it.copy(rest = null) }
    }

    fun setSkipped(position: Int, skipped: Boolean) {
        viewModelScope.launch { sessions.setSkipped(position, skipped) }
    }

    fun openFinish() = _state.update { it.copy(showFinish = true, finishError = null) }
    fun dismissFinish() = _state.update { it.copy(showFinish = false) }
    fun setDifficulty(d: Int) = _state.update { it.copy(difficulty = if (it.difficulty == d) null else d.coerceIn(1, 5)) }
    fun setNotes(n: String) = _state.update { it.copy(notes = n.take(1000)) }

    fun finish() {
        val s = _state.value
        val id = s.session?.localId ?: return
        if (s.finishedId != null) return
        viewModelScope.launch {
            when (sessions.finish(s.difficulty, s.notes)) {
                FinishOutcome.Finished -> _state.update { it.copy(showFinish = false, finishedId = id, rest = null) }
                FinishOutcome.NoCompletedSets -> _state.update { it.copy(finishError = FinishError.NO_COMPLETED_SETS) }
                FinishOutcome.NoSession -> _state.update { it.copy(showFinish = false, finishError = FinishError.NO_SESSION) }
            }
        }
    }

    internal companion object {
        const val TICK_MS = 1000L
    }
}
