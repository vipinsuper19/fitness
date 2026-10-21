package com.vipin.fitness.navigation

import com.vipin.fitness.AppViewModel
import com.vipin.fitness.core.testing.FakeSessionRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test fun startRouteDependsOnSession() {
        assertEquals(Route.SignIn(), AppViewModel(FakeSessionRepository(signedIn = false)).startRoute)
        assertEquals(Route.Onboarding, AppViewModel(FakeSessionRepository(signedIn = true)).startRoute)
    }

    @Test fun sessionExpiryResetsToSignInOnce() {
        val stack = mutableListOf<Any>(Route.Main, Route.Profile)
        AppNavigation.onSessionExpired(stack)
        assertEquals(listOf<Any>(Route.SignIn(expired = true)), stack)
        AppNavigation.onSessionExpired(stack)
        assertEquals(1, stack.size)
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
