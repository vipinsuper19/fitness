package com.vipin.fitness.core.network.auth

import com.vipin.fitness.core.network.model.AuthResponseDto
import com.vipin.fitness.core.network.model.TokenPairDto

/** The signed-in session's tokens. [accessExpiresAtEpochMs] is informational; the server's 401 decides. */
data class AuthTokens(val accessToken: String, val refreshToken: String, val accessExpiresAtEpochMs: Long) {
    override fun toString() = "AuthTokens(<redacted>, expiresAt=$accessExpiresAtEpochMs)"
}

fun TokenPairDto.toAuthTokens(nowMs: Long) = AuthTokens(accessToken, refreshToken, nowMs + expiresIn * 1000)
fun AuthResponseDto.toAuthTokens(nowMs: Long) = AuthTokens(accessToken, refreshToken, nowMs + expiresIn * 1000)

/**
 * Where the session lives. Calls are synchronous because OkHttp's interceptor and authenticator
 * run on its own background threads. Implemented by core:datastore (encrypted, on-device).
 */
interface TokenStore {
    fun get(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()

    /**
     * Atomically (under the same lock as [save] and [clear]) replaces the session with [tokens] only
     * if the stored refresh token is still [expectedRefresh]. Returns false, changing nothing, if the
     * user signed out or a newer session was saved meanwhile. Used by [TokenAuthenticator] so a
     * refresh that finishes late can never resurrect a logged-out session or clobber a newer login.
     */
    fun compareAndSet(expectedRefresh: String, tokens: AuthTokens): Boolean

    /** Atomically clears the session only if the stored refresh token is still [expectedRefresh]. */
    fun clearIf(expectedRefresh: String): Boolean
}
