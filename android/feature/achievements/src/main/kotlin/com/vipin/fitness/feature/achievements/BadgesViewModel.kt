package com.vipin.fitness.feature.achievements

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BadgesState(
    val loading: Boolean = true,
    /** Set when the last load failed; cached [badges] (if any) stay on screen. */
    val error: ApiError? = null,
    /** The server doesn't serve badges yet (404): "coming soon", no Retry. */
    val unavailable: Boolean = false,
    /** Server catalog order (stable per the contract). */
    val badges: List<Badge> = emptyList(),
    val earnedCount: Int = 0,
    /** Badges earned since the user last saw the list: shown once in a "Badge earned" sheet. */
    val celebrate: List<Badge> = emptyList(),
)

/**
 * The backend ships these endpoints after the app (spec §12), so until then they 404. That is
 * "coming soon", not an error: retrying wouldn't help.
 */
internal fun ApiError.isNotServedYet() = this is ApiError.Http && status == 404

@HiltViewModel
class BadgesViewModel @Inject constructor(
    private val repo: AchievementsRepository,
    private val tracker: EarnedBadgeTracker,
) : ViewModel() {
    private val _state = MutableStateFlow(BadgesState())
    val state: StateFlow<BadgesState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.badges()) {
                is ApiResult.Success -> {
                    val fresh = tracker.newlyEarned(r.value)
                    _state.update {
                        it.copy(
                            loading = false,
                            unavailable = false,
                            badges = r.value.items,
                            earnedCount = r.value.earnedCount,
                            celebrate = (it.celebrate + fresh).distinctBy { b -> b.code },
                        )
                    }
                }
                is ApiResult.Failure ->
                    if (r.error.isNotServedYet()) _state.update { it.copy(loading = false, unavailable = true) }
                    else _state.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    fun dismissCelebration() = _state.update { it.copy(celebrate = emptyList()) }
}
