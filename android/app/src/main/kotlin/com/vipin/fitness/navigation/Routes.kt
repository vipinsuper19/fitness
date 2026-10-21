package com.vipin.fitness.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Navigation 3 keys. Serializable so the back stack survives process death. */
sealed interface Route : NavKey {
    /** [email] pre-fills the form ("Sign in instead"); [expired] explains why the user is here. */
    @Serializable data class SignIn(val email: String? = null, val expired: Boolean = false) : Route
    @Serializable data object Register : Route
    @Serializable data object Onboarding : Route
    @Serializable data object Main : Route
    @Serializable data object Profile : Route

    // Workout (feature:workout). pickDay is a DayOfWeek value (1 = Monday) when picking for a custom plan.
    @Serializable data object WorkoutNewPlan : Route
    @Serializable data object PlanPreview : Route
    @Serializable data class ExerciseLibrary(val pickDay: Int? = null) : Route
    @Serializable data class ExerciseDetail(val id: String) : Route
}

/** Back-stack moves, kept pure so they can be unit tested. */
object AppNavigation {
    /**
     * Signed out: SignIn. Signed in and known (cached) to have finished onboarding: Main, with no
     * network needed. Otherwise Onboarding, which loads the profile and forwards to Main if the
     * server says it's complete.
     */
    fun startRoute(signedIn: Boolean, onboardingCompleted: Boolean): Route = when {
        !signedIn -> Route.SignIn()
        onboardingCompleted -> Route.Main
        else -> Route.Onboarding
    }

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

    /**
     * Each time the UI reaches STARTED. Catches an expiry signalled while nothing was collecting
     * (app in background) and a back stack restored after process death with the session gone.
     */
    fun onStarted(stack: MutableList<Any>, signedIn: Boolean) {
        val top = stack.lastOrNull()
        if (!signedIn && top !is Route.SignIn && top != Route.Register) onSessionExpired(stack)
    }

    /** Runs while STARTED: check the session once, then follow live expiry signals. */
    suspend fun guardSession(stack: MutableList<Any>, isSignedIn: () -> Boolean, expired: Flow<Unit>) {
        onStarted(stack, isSignedIn())
        expired.collect { onSessionExpired(stack) }
    }

    /** "Sign in instead" from Register: replace Register so Back doesn't return to it. */
    fun signInInstead(stack: MutableList<Any>, email: String) {
        if (stack.lastOrNull() == Route.Register) stack.removeAt(stack.lastIndex)
        if (stack.lastOrNull() is Route.SignIn) stack.removeAt(stack.lastIndex)
        stack.add(Route.SignIn(email = email.ifBlank { null }))
    }

    /** After a plan is activated, drop the workout sub-flow and land back on the tabs. */
    fun onPlanActivated(stack: MutableList<Any>) {
        val main = stack.lastIndexOf(Route.Main)
        if (main < 0) resetTo(stack, Route.Main) else while (stack.lastIndex > main) stack.removeAt(stack.lastIndex)
    }

    /** Pop the top entry, e.g. after an exercise is picked. Never empties the stack. */
    fun pop(stack: MutableList<Any>) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}
