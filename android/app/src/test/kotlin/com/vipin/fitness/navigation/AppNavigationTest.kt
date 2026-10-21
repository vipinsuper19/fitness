package com.vipin.fitness.navigation

import com.vipin.fitness.AppViewModel
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppNavigationTest {
    @get:Rule val main = MainDispatcherRule()
    private val profiles = FakeProfileRepository()

    private fun appVm(signedIn: Boolean, completed: Boolean) =
        AppViewModel(FakeSessionRepository(signedIn), profiles, FakeProfileFlagsCache(completed))

    @Test fun signedInLaunchRecordsTheActiveUserForConsent() {
        val consent = com.vipin.fitness.core.testing.FakeConsentRepository()
        AppViewModel(FakeSessionRepository(true), profiles, FakeProfileFlagsCache(true), consent)
        assertEquals(1, consent.refreshes)
        val signedOut = com.vipin.fitness.core.testing.FakeConsentRepository()
        AppViewModel(FakeSessionRepository(false), profiles, FakeProfileFlagsCache(false), signedOut)
        assertEquals(0, signedOut.refreshes)
    }

    @Test fun signedOutStartsAtSignIn() {
        assertEquals(Route.SignIn(), appVm(signedIn = false, completed = true).startRoute.value)
        assertEquals(0, profiles.gets)
    }

    @Test fun signedInWithoutCacheGoesThroughOnboarding() {
        assertEquals(Route.Onboarding, appVm(signedIn = true, completed = false).startRoute.value)
    }

    @Test fun knownCompleteGoesStraightToMainAndRefreshesInBackground() {
        assertEquals(Route.Main, appVm(signedIn = true, completed = true).startRoute.value)
        assertEquals(1, profiles.gets)
    }

    @Test fun offlineLaunchWhenCompleteStillOpensMain() {
        profiles.getResult = { ApiResult.Failure(ApiError.Network) }
        assertEquals(Route.Main, appVm(signedIn = true, completed = true).startRoute.value)
    }

    @Test fun sessionExpiryResetsToSignInOnce() {
        val stack = mutableListOf<Any>(Route.Main, Route.Profile)
        AppNavigation.onSessionExpired(stack)
        assertEquals(listOf<Any>(Route.SignIn(expired = true)), stack)
        AppNavigation.onSessionExpired(stack)
        assertEquals(1, stack.size)
    }

    @Test fun expirySignalledWhileNobodyCollectedIsCaughtOnStart() = runTest {
        val session = FakeSessionRepository(signedIn = true)
        val stack = mutableListOf<Any>(Route.Main)
        // The refresh is rejected while the app is stopped: tokens cleared, signal emitted with no collector.
        session.signedIn = false
        assertEquals(0, session.sessionExpired.subscriptionCount.value)
        session.sessionExpired.tryEmit(Unit) // lost: SessionEvents has no replay

        val job = backgroundScope.launch { AppNavigation.guardSession(stack, session::isSignedIn, session.sessionExpired) }
        runCurrent()
        assertEquals(listOf<Any>(Route.SignIn(expired = true)), stack)
        job.cancel()
    }

    @Test fun liveExpiryWhileStartedResets() = runTest {
        val session = FakeSessionRepository(signedIn = true)
        val stack = mutableListOf<Any>(Route.Main, Route.Profile)
        backgroundScope.launch { AppNavigation.guardSession(stack, session::isSignedIn, session.sessionExpired) }
        runCurrent()
        assertEquals(2, stack.size) // still signed in: nothing happens on start
        session.sessionExpired.emit(Unit)
        runCurrent()
        assertEquals(listOf<Any>(Route.SignIn(expired = true)), stack)
    }

    @Test fun restoredBackStackAfterProcessDeathWithNoSession() {
        val restored = mutableListOf<Any>(Route.Main, Route.Profile)
        AppNavigation.onStarted(restored, signedIn = false)
        assertEquals(listOf<Any>(Route.SignIn(expired = true)), restored)
    }

    @Test fun onStartedLeavesAuthScreensAndSignedInUsersAlone() {
        val register = mutableListOf<Any>(Route.SignIn(), Route.Register)
        AppNavigation.onStarted(register, signedIn = false)
        assertEquals(listOf<Any>(Route.SignIn(), Route.Register), register)
        val main = mutableListOf<Any>(Route.Main)
        AppNavigation.onStarted(main, signedIn = true)
        assertEquals(listOf<Any>(Route.Main), main)
    }

    @Test fun authenticatedClearsAuthScreens() {
        val stack = mutableListOf<Any>(Route.SignIn(), Route.Register)
        AppNavigation.onAuthenticated(stack)
        assertEquals(listOf<Any>(Route.Onboarding), stack)
        AppNavigation.onOnboardingFinished(stack)
        assertEquals(listOf<Any>(Route.Main), stack)
    }

    @Test fun signInInsteadReplacesRegisterAndCarriesEmail() {
        val stack = mutableListOf<Any>(Route.SignIn(), Route.Register)
        AppNavigation.signInInstead(stack, "v@x.in")
        assertEquals(listOf<Any>(Route.SignIn(email = "v@x.in")), stack)
    }

    @Test fun signOutGoesToSignIn() {
        val stack = mutableListOf<Any>(Route.Main, Route.Profile)
        AppNavigation.onSignedOut(stack)
        assertEquals(listOf<Any>(Route.SignIn()), stack)
    }
}
