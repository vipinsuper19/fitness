package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.auth.AuthApi
import com.vipin.fitness.core.network.auth.SessionEvents
import com.vipin.fitness.core.network.auth.TokenStore
import com.vipin.fitness.core.network.auth.toAuthTokens
import com.vipin.fitness.core.network.model.AuthResponseDto
import com.vipin.fitness.core.network.model.LoginRequestDto
import com.vipin.fitness.core.network.model.RefreshRequestDto
import com.vipin.fitness.core.network.model.RegisterRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why sign-in or sign-up failed, in terms the UI can act on. */
sealed interface AuthFailure {
    data object InvalidCredentials : AuthFailure
    /** Register: account exists (also a retry after a sign-up that did go through). Offer sign-in. */
    data object EmailTaken : AuthFailure
    data class Validation(val message: String) : AuthFailure
    data object RateLimited : AuthFailure
    data object Network : AuthFailure
    data class Other(val message: String) : AuthFailure
}

sealed interface AuthOutcome {
    data object Success : AuthOutcome
    data class Failure(val reason: AuthFailure) : AuthOutcome
}

interface SessionRepository {
    fun isSignedIn(): Boolean
    /** Fires when the refresh token is rejected or the stored session is unreadable. */
    val sessionExpired: Flow<Unit>
    suspend fun signIn(email: String, password: String): AuthOutcome
    /** [idempotencyKey]: one per sign-up attempt, reused on a retry of the same attempt. */
    suspend fun register(email: String, password: String, displayName: String?, idempotencyKey: String): AuthOutcome
    suspend fun signOut()
}

@Singleton
class DefaultSessionRepository @Inject constructor(
    private val api: AuthApi,
    private val tokens: TokenStore,
    private val events: SessionEvents,
    private val json: Json,
    private val clock: Clock,
) : SessionRepository {
    override fun isSignedIn() = tokens.get() != null
    override val sessionExpired: Flow<Unit> = events.expired

    override suspend fun signIn(email: String, password: String) = authenticate {
        api.login(LoginRequestDto(email.trim(), password))
    }

    override suspend fun register(email: String, password: String, displayName: String?, idempotencyKey: String) =
        authenticate {
            api.register(RegisterRequestDto(email.trim(), password, displayName?.trim()?.ifEmpty { null }), idempotencyKey)
        }

    override suspend fun signOut() {
        val refresh = tokens.get()?.refreshToken
        // Best effort: revoke on the server, but always forget locally.
        if (refresh != null) apiCall(json) { api.logout(RefreshRequestDto(refresh)) }
        withContext(Dispatchers.IO) { tokens.clear() }
    }

    private suspend fun authenticate(call: suspend () -> AuthResponseDto): AuthOutcome =
        when (val r = apiCall(json, call)) {
            is ApiResult.Success -> {
                withContext(Dispatchers.IO) { tokens.save(r.value.toAuthTokens(clock.millis())) }
                AuthOutcome.Success
            }
            is ApiResult.Failure -> AuthOutcome.Failure(r.error.toAuthFailure())
        }
}

internal fun ApiError.toAuthFailure(): AuthFailure = when (this) {
    ApiError.Network -> AuthFailure.Network
    is ApiError.Unexpected -> AuthFailure.Other(cause.message ?: "Unexpected error")
    is ApiError.Http -> when {
        code == "INVALID_CREDENTIALS" -> AuthFailure.InvalidCredentials
        code == "EMAIL_TAKEN" -> AuthFailure.EmailTaken
        status == 429 -> AuthFailure.RateLimited
        status == 400 -> AuthFailure.Validation(message)
        else -> AuthFailure.Other(message)
    }
}

fun newIdempotencyKey(): String = UUID.randomUUID().toString()
