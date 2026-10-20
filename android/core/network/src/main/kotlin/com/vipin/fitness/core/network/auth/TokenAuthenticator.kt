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

            return when (val result = refresher.refresh(current.refreshToken)) {
                is RefreshResult.Success -> {
                    tokenStore.save(result.tokens)
                    request.withBearer(result.tokens.accessToken)
                }
                RefreshResult.Rejected -> {
                    tokenStore.clear()
                    sessionEvents.notifyExpired()
                    null
                }
                RefreshResult.Failed -> null
            }
        }
    }

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
