package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/*
 * DTOs for the auth endpoints, hand-written from contracts/openapi.yaml (T5, merged in #4).
 * Keep them in sync with that file; everything else depends on AuthTokens, not on these.
 */

@Serializable
data class RegisterRequestDto(val email: String, val password: String, val displayName: String? = null)

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class RefreshRequestDto(val refreshToken: String)

@Serializable
data class TokenPairDto(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)

@Serializable
data class UserDto(val id: String, val email: String, val displayName: String? = null, val createdAt: String)

@Serializable
data class AuthResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: UserDto,
)

/** Every non-2xx body (contract `Error`). Clients branch on [code], never on [message]. */
@Serializable
data class ErrorDto(
    val code: String,
    val message: String,
    val requestId: String? = null,
    /** Code-specific extras; tolerant (any shape). For RATE_LIMITED: `retryAfterSeconds`. */
    val details: kotlinx.serialization.json.JsonObject? = null,
)

/** `details.retryAfterSeconds` (contract: every 429, equal to Retry-After), if a positive integer. */
fun kotlinx.serialization.json.JsonObject?.retryAfterSeconds(): Long? =
    (this?.get("retryAfterSeconds") as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 }
