package com.vipin.fitness.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.vipin.fitness.feature.auth.RegisterRoute
import com.vipin.fitness.feature.auth.SignInRoute
import com.vipin.fitness.feature.onboarding.OnboardingRoute
import com.vipin.fitness.feature.onboarding.ProfileEditRoute
import kotlinx.coroutines.flow.Flow

@Suppress("UNCHECKED_CAST")
private fun MutableList<NavKey>.asAny() = this as MutableList<Any>

@Composable
fun FitnessNavDisplay(startRoute: Route, sessionExpired: Flow<Unit>) {
    val backStack = rememberNavBackStack(startRoute)
    val stack = backStack.asAny()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sessionExpired, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            sessionExpired.collect { AppNavigation.onSessionExpired(stack) }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key ->
            when (val route = key as Route) {
                is Route.SignIn -> NavEntry(key) {
                    SignInRoute(
                        prefillEmail = route.email,
                        sessionExpired = route.expired,
                        onSignedIn = { AppNavigation.onAuthenticated(stack) },
                        onRegister = { backStack.add(Route.Register) },
                    )
                }
                Route.Register -> NavEntry(key) {
                    RegisterRoute(
                        onRegistered = { AppNavigation.onAuthenticated(stack) },
                        onSignInInstead = { email -> AppNavigation.signInInstead(stack, email) },
                    )
                }
                Route.Onboarding -> NavEntry(key) {
                    OnboardingRoute(onFinished = { AppNavigation.onOnboardingFinished(stack) })
                }
                Route.Main -> NavEntry(key) {
                    FitnessApp(onOpenProfile = { backStack.add(Route.Profile) })
                }
                Route.Profile -> NavEntry(key) {
                    ProfileEditRoute(onSignedOut = { AppNavigation.onSignedOut(stack) })
                }
            }
        },
    )
}
