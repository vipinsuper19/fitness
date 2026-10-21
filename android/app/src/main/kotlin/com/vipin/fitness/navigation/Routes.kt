package com.vipin.fitness.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Navigation 3 keys. Serializable so the back stack survives process death. */
sealed interface Route : NavKey {
    /** [email] pre-fills the form ("Sign in instead"); [expired] explains why the user is here. */
    @Serializable data class SignIn(val email: String? = null, val expired: Boolean = false) : Route
    @Serializable data object Register : Route
    @Serializable data object Onboarding : Route
    @Serializable data object Main : Route
    @Serializable data object Profile : Route
}

/** Back-stack moves, kept pure so they can be unit tested. */
object AppNavigation {
    /** Signed-in users go through Onboarding, which forwards to Main if it's already complete. */
    fun startRoute(signedIn: Boolean): Route = if (signedIn) Route.Onboarding else Route.SignIn()

    fun resetTo(stack: MutableList<Any>, route: Route) {
        stack.clear()
        stack.add(route)
    }

    fun onAuthenticated(stack: MutableList<Any>) = resetTo(stack, Route.Onboarding)
    fun onOnboardingFinished(stack: MutableList<Any>) = resetTo(stack, Route.Main)
    fun onSignedOut(stack: MutableList<Any>) = resetTo(stack, Route.SignIn())
    fun onSessionExpired(stack: MutableList<Any>) {
        if (stack.lastOrNull() is Route.SignIn) return // already there (e.g. two expiry signals)
        resetTo(stack, Route.SignIn(expired = true))
    }

    /** "Sign in instead" from Register: replace Register so Back doesn't return to it. */
    fun signInInstead(stack: MutableList<Any>, email: String) {
        if (stack.lastOrNull() == Route.Register) stack.removeAt(stack.lastIndex)
        if (stack.lastOrNull() is Route.SignIn) stack.removeAt(stack.lastIndex)
        stack.add(Route.SignIn(email = email.ifBlank { null }))
    }
}
