package com.vipin.fitness.core.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.vipin.fitness.core.network.auth.AuthTokens
import com.vipin.fitness.core.network.auth.SessionEvents
import com.vipin.fitness.core.network.auth.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session tokens, encrypted with [TokenCipher] and kept in a private SharedPreferences file
 * (excluded from backup by the app's backup rules). Synchronous because OkHttp calls it from
 * its own threads; reads are served from memory after the first load.
 *
 * A blob that fails to decrypt (Keystore key lost or invalidated, data tampered) is dropped and
 * [SessionEvents.notifyExpired] sends the user back to sign-in.
 */
@Singleton
class EncryptedTokenStore internal constructor(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher,
    private val sessionEvents: SessionEvents,
) : TokenStore {
    @Inject constructor(@ApplicationContext context: Context, cipher: TokenCipher, sessionEvents: SessionEvents) :
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), cipher, sessionEvents)

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

    override fun save(tokens: AuthTokens) = synchronized(lock) { write(tokens) }

    override fun compareAndSet(expectedRefresh: String, tokens: AuthTokens): Boolean = synchronized(lock) {
        if (get()?.refreshToken != expectedRefresh) return@synchronized false
        write(tokens)
        true
    }

    override fun clearIf(expectedRefresh: String): Boolean = synchronized(lock) {
        if (get()?.refreshToken != expectedRefresh) return@synchronized false
        clear()
        true
    }

    /** Caller holds [lock]. */
    private fun write(tokens: AuthTokens) {
        val json = JSONObject()
            .put("a", tokens.accessToken)
            .put("r", tokens.refreshToken)
            .put("e", tokens.accessExpiresAtEpochMs)
            .toString()
        val sealed = try {
            cipher.encrypt(json.toByteArray())
        } catch (e: GeneralSecurityException) {
            // Even a regenerated key failed: we can't keep the session safely, so end it.
            dropSession()
            return
        }
        val blob = Base64.encodeToString(sealed, Base64.NO_WRAP)
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
            dropSession()
            null
        }
    }

    private fun dropSession() {
        prefs.edit(commit = true) { remove(KEY) }
        cached = null
        loaded = true
        sessionEvents.notifyExpired()
    }

    private companion object {
        const val FILE = "fitness_session"
        const val KEY = "tokens"
    }
}
