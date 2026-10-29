package com.vipin.fitness.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.ProfileApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consent to the privacy policy, terms and health-data use. Stored on this device only, per user
 * and per [CONSENT_VERSION]: each account answers once per version. Sign-out forgets who is
 * active but keeps the per-user records, so the same person signing back in isn't asked again.
 * Raising [CONSENT_VERSION] asks everyone again (old records no longer count).
 */
interface ConsentRepository {
    /** True only when the signed-in user is known and has accepted the current version. */
    suspend fun hasConsented(): Boolean
    /** Records consent for the signed-in user. False (nothing saved) if that user can't be identified. */
    suspend fun accept(): Boolean
    /** App start: records which user is signed in if the device doesn't know yet (sessions from before consent shipped). */
    suspend fun refreshActiveUser()
    /** Account deleted: drop that user's records (all versions) as well as the active pointer. */
    suspend fun forgetActiveUser()
}

const val CONSENT_VERSION = 1

/** Who is signed in, asked from the server (GET /me/profile → userId). Null when unknown (offline, signed out). */
fun interface SignedInUserId {
    suspend fun fetch(): String?
}

@Singleton
class ProfileSignedInUserId @Inject constructor(
    private val api: ProfileApi,
    private val tokens: TokenStore,
    private val json: Json,
) : SignedInUserId {
    override suspend fun fetch(): String? {
        if (withContext(Dispatchers.IO) { tokens.get() } == null) return null
        return (apiCall(json) { api.get().userId } as? ApiResult.Success)?.value
    }
}

class DataStoreConsentRepository(
    private val store: DataStore<Preferences>,
    private val clock: Clock,
    private val signedInUser: SignedInUserId,
    private val version: Int = CONSENT_VERSION,
) : ConsentRepository, UserScopedState {
    override suspend fun hasConsented(): Boolean {
        val user = activeUser() ?: return false
        return store.data.first()[acceptedAt(user, version)] != null
    }

    override suspend fun accept(): Boolean {
        val user = activeUser() ?: return false
        store.edit { it[acceptedAt(user, version)] = clock.millis() }
        return true
    }

    override suspend fun refreshActiveUser() {
        activeUser()
    }

    override suspend fun forgetActiveUser() {
        store.edit { prefs ->
            val user = prefs[ACTIVE_USER] ?: return@edit
            prefs.asMap().keys.filter { it.name.endsWith(suffix(user)) }.forEach { prefs.remove(it) }
            prefs.remove(ACTIVE_USER)
        }
    }

    override suspend fun onSignedIn(userId: String) {
        store.edit { it[ACTIVE_USER] = userId }
    }

    override suspend fun clearUserState() {
        store.edit { it.remove(ACTIVE_USER) }
    }

    /** The recorded user, or (signed in before this was recorded) the server's answer, saved for next time. */
    private suspend fun activeUser(): String? {
        store.data.first()[ACTIVE_USER]?.let { return it }
        val id = signedInUser.fetch() ?: return null
        store.edit { it[ACTIVE_USER] = id }
        return id
    }

    private companion object {
        val ACTIVE_USER = stringPreferencesKey("active_user")
        fun suffix(user: String) = "_accepted_at_$user"
        fun acceptedAt(user: String, version: Int) = longPreferencesKey("v$version${suffix(user)}")
    }
}
