package com.vipin.fitness.feature.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.achievements.AchievementErrors
import com.vipin.fitness.core.data.achievements.AchievementsRepository
import com.vipin.fitness.core.data.achievements.Challenge
import com.vipin.fitness.core.data.achievements.ChallengeStatus
import com.vipin.fitness.core.data.achievements.ChallengeTemplate
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** One-off messages after a join or leave, branched on the contract's error codes. */
enum class ChallengeMessage { JOINED, LEFT, ALREADY_JOINED, LIMIT_REACHED, NOT_ACTIVE, OFFLINE, FAILED }

data class ChallengesState(
    val loading: Boolean = true,
    val loadError: ApiError? = null,
    val templates: List<ChallengeTemplate> = emptyList(),
    val active: List<Challenge> = emptyList(),
    /** Completed, expired, left, and any status this build doesn't know. */
    val history: List<Challenge> = emptyList(),
    val historyCursor: String? = null,
    /** Chosen target per template code; defaults to the template's first option. */
    val selectedTarget: Map<String, Int> = emptyMap(),
    /** Template code being joined / challenge id being left: disables the button. */
    val joining: String? = null,
    val leaving: String? = null,
    /** Leave asks first. */
    val confirmLeave: Challenge? = null,
    val message: ChallengeMessage? = null,
) {
    fun target(t: ChallengeTemplate): Int? = selectedTarget[t.code] ?: t.options.firstOrNull()?.target
    fun isJoined(t: ChallengeTemplate) = active.any { it.templateCode == t.code }
}

@HiltViewModel
class ChallengesViewModel @Inject constructor(
    private val repo: AchievementsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ChallengesState())
    val state: StateFlow<ChallengesState> = _state.asStateFlow()

    /**
     * One Idempotency-Key per user action, kept while its outcome is unknown (offline, timeout,
     * in progress) so a retry of the same action is replayed by the server, not repeated.
     */
    private val pendingKeys = mutableMapOf<String, String>()

    init {
        refresh()
    }

    fun now() = clock.instant()

    fun refresh() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val templates = async { repo.templates() }
            val challenges = async { repo.challenges() }
            val t = templates.await()
            val c = challenges.await()
            _state.update { s ->
                var n = s.copy(loading = false)
                if (t is ApiResult.Success) n = n.copy(templates = t.value)
                if (c is ApiResult.Success) {
                    val (active, rest) = c.value.items.partition { it.status == ChallengeStatus.ACTIVE }
                    n = n.copy(active = active, history = rest, historyCursor = c.value.nextCursor)
                }
                val err = (t as? ApiResult.Failure)?.error ?: (c as? ApiResult.Failure)?.error
                n.copy(loadError = err)
            }
        }
    }

    fun loadMoreHistory() {
        val cursor = _state.value.historyCursor ?: return
        viewModelScope.launch {
            val r = repo.challenges(cursor = cursor)
            if (r is ApiResult.Success) {
                _state.update { s ->
                    val (active, rest) = r.value.items.partition { it.status == ChallengeStatus.ACTIVE }
                    s.copy(
                        active = (s.active + active).distinctBy { it.id },
                        history = (s.history + rest).distinctBy { it.id },
                        historyCursor = r.value.nextCursor,
                    )
                }
            }
        }
    }

    fun selectTarget(templateCode: String, target: Int) =
        _state.update { it.copy(selectedTarget = it.selectedTarget + (templateCode to target)) }

    fun join(t: ChallengeTemplate) {
        val s = _state.value
        if (s.joining != null) return
        val target = s.target(t) ?: return
        val action = "join:${t.code}:$target"
        val key = pendingKeys.getOrPut(action) { newIdempotencyKey() }
        _state.update { it.copy(joining = t.code) }
        viewModelScope.launch {
            val r = repo.join(t.code, target, key)
            settle(action, r)
            _state.update { st ->
                when (r) {
                    is ApiResult.Success -> st.copy(joining = null, active = listOf(r.value) + st.active.filter { it.id != r.value.id }, message = ChallengeMessage.JOINED)
                    is ApiResult.Failure -> st.copy(joining = null, message = r.error.toMessage())
                }
            }
            // Already joined (e.g. on another device): reload so the card shows up.
            if ((r as? ApiResult.Failure)?.error.let { it is ApiError.Http && it.code == AchievementErrors.ALREADY_JOINED }) refresh()
        }
    }

    fun askLeave(c: Challenge) = _state.update { it.copy(confirmLeave = c) }
    fun cancelLeave() = _state.update { it.copy(confirmLeave = null) }

    fun confirmLeave() {
        val c = _state.value.confirmLeave ?: return
        if (_state.value.leaving != null) return
        val action = "leave:${c.id}"
        val key = pendingKeys.getOrPut(action) { newIdempotencyKey() }
        _state.update { it.copy(confirmLeave = null, leaving = c.id) }
        viewModelScope.launch {
            val r = repo.leave(c.id, key)
            settle(action, r)
            _state.update { st ->
                when (r) {
                    is ApiResult.Success -> st.copy(
                        leaving = null,
                        active = st.active.filter { it.id != c.id },
                        history = listOf(r.value) + st.history.filter { it.id != c.id },
                        message = ChallengeMessage.LEFT,
                    )
                    is ApiResult.Failure -> st.copy(leaving = null, message = r.error.toMessage())
                }
            }
            // Completed or expired meanwhile: reload to show its real status.
            if ((r as? ApiResult.Failure)?.error.let { it is ApiError.Http && it.code == AchievementErrors.NOT_ACTIVE }) refresh()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Forget the key once the server gave a final answer; keep it while the outcome is unknown. */
    private fun settle(action: String, r: ApiResult<*>) {
        val unknown = r is ApiResult.Failure && when (val e = r.error) {
            is ApiError.Http -> e.status >= 500 || e.status == 408 || e.status == 429 || e.code == "IDEMPOTENCY_IN_PROGRESS"
            else -> true
        }
        if (!unknown) pendingKeys.remove(action)
    }
}

internal fun ApiError.toMessage(): ChallengeMessage = when (this) {
    ApiError.Network -> ChallengeMessage.OFFLINE
    is ApiError.Http -> when (code) {
        AchievementErrors.ALREADY_JOINED -> ChallengeMessage.ALREADY_JOINED
        AchievementErrors.LIMIT_REACHED -> ChallengeMessage.LIMIT_REACHED
        AchievementErrors.NOT_ACTIVE -> ChallengeMessage.NOT_ACTIVE
        else -> ChallengeMessage.FAILED
    }
    is ApiError.Unexpected -> ChallengeMessage.FAILED
}
