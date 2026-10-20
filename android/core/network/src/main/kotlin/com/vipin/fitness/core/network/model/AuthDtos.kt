package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/*
 * DTOs for the auth endpoints, hand-written from the draft T5 contract
 * (contracts/openapi.yaml on PR #4, not yet merged). If the contract changes before it lands,
 * only these classes and AuthApi need updating; everything else depends on AuthTokens.
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
data class ErrorDto(val code: String, val message: String, val requestId: String? = null)
