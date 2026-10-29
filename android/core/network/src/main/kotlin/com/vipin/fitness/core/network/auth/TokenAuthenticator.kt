package com.vipin.fitness.core.network.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/**
 * Handles 401s by refreshing the access token once and retrying.
 *
 * Single-flight: refresh tokens are single-use and reusing one revokes the whole family, so
 * concurrent 401s must not each call /auth/refresh. Refreshes are serialized on [lock]; a thread
 * that waited compares the token its request was sent with against the stored one, and if another
 * thread already rotated it, it just retries with the new token.
 */
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val refresher: TokenRefresher,
    private val sessionEvents: SessionEvents,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        val sentToken = request.bearerToken() ?: return null // call wasn't authenticated; nothing to refresh
        if (response.priorCount() >= MAX_RETRIES) return null

        synchronized(lock) {
            val current = tokenStore.get() ?: return null // signed out meanwhile
            if (current.accessToken != sentToken) return request.withBearer(current.accessToken)

            val presented = current.refreshToken
            return when (val result = refresher.refresh(presented)) {
                // Compare-and-save: if the user signed out (or signed in again) while the refresh was
                // in flight, the result belongs to a dead session and must not overwrite the store.
                is RefreshResult.Success ->
                    if (tokenStore.compareAndSet(presented, result.tokens)) request.withBearer(result.tokens.accessToken)
                    else retryWithCurrent(request)
                RefreshResult.Rejected ->
                    if (tokenStore.clearIf(presented)) {
                        sessionEvents.notifyExpired()
                        null
                    } else {
                        retryWithCurrent(request) // deliberate logout or newer login: no expiry signal
                    }
                RefreshResult.Failed -> null
            }
        }
    }

    /** Store changed under us: retry once with a newer session's token, or give up if signed out. */
    private fun retryWithCurrent(request: Request): Request? =
        tokenStore.get()?.let { request.withBearer(it.accessToken) }

    private fun Response.priorCount(): Int {
        var n = 0
        var r = priorResponse
        while (r != null) { n++; r = r.priorResponse }
        return n
    }

    private companion object {
        const val MAX_RETRIES = 1
    }
}
