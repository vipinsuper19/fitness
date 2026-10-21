package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.achievements.AchievementsRepository
import com.vipin.fitness.core.data.achievements.Badge
import com.vipin.fitness.core.data.achievements.EarnedBadgeTracker
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The badge check on the workout-complete screen. */
sealed interface SessionBadgesState {
    /** Not checked yet (waiting for the completion to sync), or checking. */
    data object Idle : SessionBadgesState
    /** Nothing new, or the check failed: the summary just doesn't show a badge section. */
    data object None : SessionBadgesState
    /** Newly earned and not shown before; already marked seen. */
    data class Earned(val badges: List<Badge>) : SessionBadgesState
    /** GET /badges 404s: the backend doesn't serve badges yet. */
    data object Unavailable : SessionBadgesState
}

@HiltViewModel
class SessionBadgesViewModel @Inject constructor(
    private val repo: AchievementsRepository,
    private val tracker: EarnedBadgeTracker,
) : ViewModel() {
    private val _state = MutableStateFlow<SessionBadgesState>(SessionBadgesState.Idle)
    val state: StateFlow<SessionBadgesState> = _state.asStateFlow()
    private var running = false
    private var settled = false

    /**
     * Badges are awarded by the server when the completion arrives, so call this once the
     * session has synced. Runs at most once to a final answer; a failed check can be retried by
     * the next call, and never breaks the summary.
     */
    fun check() {
        if (running || settled) return
        running = true
        viewModelScope.launch {
            try {
                _state.value = when (val r = repo.badges()) {
                    is ApiResult.Success -> {
                        settled = true
                        val fresh = tracker.newlyEarned(r.value)
                        if (fresh.isEmpty()) SessionBadgesState.None else SessionBadgesState.Earned(fresh)
                    }
                    is ApiResult.Failure -> {
                        val notServed = r.error.let { it is ApiError.Http && it.status == 404 }
                        settled = notServed
                        if (notServed) SessionBadgesState.Unavailable else SessionBadgesState.None
                    }
                }
            } finally {
                running = false
            }
        }
    }
}
