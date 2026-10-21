package com.vipin.fitness.core.data

/**
 * In-memory state that belongs to the signed-in user (active plan, drafts...). Every binding
 * (`@Binds @IntoSet`) is cleared on sign-out and on a successful sign-in or registration, so the
 * next user never sees the previous user's data.
 */
interface UserScopedState {
    /** Sign-out (after the user confirmed losing anything unsynced). */
    suspend fun clearUserState()

    /** Sign-in or registration succeeded. Default: start clean. State that is kept per user overrides this. */
    suspend fun onSignedIn(userId: String) = clearUserState()
}
