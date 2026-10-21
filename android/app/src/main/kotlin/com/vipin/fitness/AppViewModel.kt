package com.vipin.fitness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.core.datastore.ProfileFlagsCache
import com.vipin.fitness.navigation.AppNavigation
import com.vipin.fitness.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val session: SessionRepository,
    private val profiles: ProfileRepository,
    private val cache: ProfileFlagsCache,
) : ViewModel() {
    private val _startRoute = MutableStateFlow<Route?>(null)

    /** Null until the local cache has been read (a few ms); the UI shows nothing until then. */
    val startRoute: StateFlow<Route?> = _startRoute.asStateFlow()

    /** Refresh token rejected or stored session unreadable while the app is running. */
    val sessionExpired: Flow<Unit> = session.sessionExpired

    init {
        viewModelScope.launch {
            val signedIn = session.isSignedIn()
            val completed = signedIn && cache.onboardingCompleted()
            _startRoute.value = AppNavigation.startRoute(signedIn, completed)
            // Known-complete users skip the network on launch; refresh the profile (and so the
            // cache) in the background. A failure here is fine: Main works offline.
            if (completed) launch { profiles.get() }
        }
    }

    fun isSignedIn(): Boolean = session.isSignedIn()
}
