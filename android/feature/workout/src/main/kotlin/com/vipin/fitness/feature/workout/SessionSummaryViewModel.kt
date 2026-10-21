package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.session.WorkoutSession
import com.vipin.fitness.core.data.session.WorkoutSessionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SessionSummaryState(val session: WorkoutSession? = null, val pendingWrites: Int = 0) {
    /** XP is only known once the server has the completion (it awards it, never the client). */
    val xpPending get() = session?.xpAwarded == null
}

@HiltViewModel(assistedFactory = SessionSummaryViewModel.Factory::class)
class SessionSummaryViewModel @AssistedInject constructor(
    sessions: WorkoutSessionRepository,
    @Assisted localId: String,
) : ViewModel() {
    val state: StateFlow<SessionSummaryState> =
        combine(sessions.session(localId), sessions.pendingWrites) { s, n -> SessionSummaryState(s, n) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SessionSummaryState())

    @AssistedFactory
    interface Factory {
        fun create(localId: String): SessionSummaryViewModel
    }
}
