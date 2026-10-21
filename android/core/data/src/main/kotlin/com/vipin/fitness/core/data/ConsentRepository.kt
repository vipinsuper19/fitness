package com.vipin.fitness.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Onboarding consent (privacy policy + terms + health-data use). Stored on this device only, per
 * user: each account that signs in here answers once per [CONSENT_VERSION]. Sign-out forgets who
 * is active but keeps the per-user records, so the same person signing back in isn't asked again.
 * Bump [CONSENT_VERSION] when the policy text changes materially to ask everyone again.
 */
interface ConsentRepository {
    /** False when nobody is signed in on this device. */
    suspend fun hasConsented(): Boolean
    /** Records consent for the signed-in user. No-op when nobody is signed in. */
    suspend fun accept()
}

const val CONSENT_VERSION = 1

@Singleton
class DataStoreConsentRepository @Inject constructor(
    private val store: DataStore<Preferences>,
    private val clock: Clock,
) : ConsentRepository, UserScopedState {
    override suspend fun hasConsented(): Boolean {
        val prefs = store.data.first()
        val user = prefs[ACTIVE_USER] ?: return false
        return prefs[acceptedAt(user)] != null
    }

    override suspend fun accept() {
        store.edit { prefs -> prefs[ACTIVE_USER]?.let { prefs[acceptedAt(it)] = clock.millis() } }
    }

    override suspend fun onSignedIn(userId: String) {
        store.edit { it[ACTIVE_USER] = userId }
    }

    override suspend fun clearUserState() {
        store.edit { it.remove(ACTIVE_USER) }
    }

    private companion object {
        val ACTIVE_USER = stringPreferencesKey("active_user")
        fun acceptedAt(user: String) = longPreferencesKey("v${CONSENT_VERSION}_accepted_at_$user")
    }
}
