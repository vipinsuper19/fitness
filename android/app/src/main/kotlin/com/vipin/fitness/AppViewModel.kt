package com.vipin.fitness

import androidx.lifecycle.ViewModel
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.navigation.AppNavigation
import com.vipin.fitness.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(session: SessionRepository) : ViewModel() {
    val startRoute: Route = AppNavigation.startRoute(session.isSignedIn())

    /** Refresh token rejected or stored session unreadable: the UI resets to sign-in. */
    val sessionExpired: Flow<Unit> = session.sessionExpired
}
