package com.vipin.fitness.core.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.vipin.fitness.core.network.auth.AuthTokens
import com.vipin.fitness.core.network.auth.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session tokens, encrypted with [TokenCipher] and kept in a private SharedPreferences file
 * (excluded from backup by the app's backup rules). Synchronous because OkHttp calls it from
 * its own threads; reads are served from memory after the first load.
 *
 * A blob that fails to decrypt (key wiped, app data restored elsewhere) is dropped and the user
 * signs in again.
 */
@Singleton
class EncryptedTokenStore internal constructor(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher,
) : TokenStore {
    @Inject constructor(@ApplicationContext context: Context, cipher: TokenCipher) :
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), cipher)

    private val lock = Any()
    private var loaded = false
    private var cached: AuthTokens? = null

    override fun get(): AuthTokens? = synchronized(lock) {
        if (!loaded) {
            cached = load()
            loaded = true
        }
        cached
    }

    override fun save(tokens: AuthTokens) = synchronized(lock) {
        val json = JSONObject()
            .put("a", tokens.accessToken)
            .put("r", tokens.refreshToken)
            .put("e", tokens.accessExpiresAtEpochMs)
            .toString()
        val blob = Base64.encodeToString(cipher.encrypt(json.toByteArray()), Base64.NO_WRAP)
        prefs.edit(commit = true) { putString(KEY, blob) } // commit: a lost rotated refresh token logs the user out
        cached = tokens
        loaded = true
    }

    override fun clear() = synchronized(lock) {
        prefs.edit(commit = true) { remove(KEY) }
        cached = null
        loaded = true
    }

    private fun load(): AuthTokens? {
        val blob = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(String(cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP))))
            AuthTokens(o.getString("a"), o.getString("r"), o.getLong("e"))
        }.getOrElse {
            prefs.edit(commit = true) { remove(KEY) }
            null
        }
    }

    private companion object {
        const val FILE = "fitness_session"
        const val KEY = "tokens"
    }
}
