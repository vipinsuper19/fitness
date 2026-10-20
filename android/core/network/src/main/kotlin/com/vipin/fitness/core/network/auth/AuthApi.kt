package com.vipin.fitness.core.network.auth

import com.vipin.fitness.core.network.model.AuthResponseDto
import com.vipin.fitness.core.network.model.LoginRequestDto
import com.vipin.fitness.core.network.model.RefreshRequestDto
import com.vipin.fitness.core.network.model.RegisterRequestDto
import com.vipin.fitness.core.network.model.TokenPairDto
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Auth endpoints from contracts/openapi.yaml. Paths are relative to `.../api/v1/`.
 * Only register takes an Idempotency-Key; login and refresh take none (token responses are never
 * stored or replayed).
 */
interface AuthApi {
    /**
     * The key only rejects a concurrent duplicate (409 IDEMPOTENCY_IN_PROGRESS). Retrying after the
     * first attempt finished returns 201 with fresh tokens if it hadn't created the account, or
     * 409 EMAIL_TAKEN if it had: the caller should then offer sign-in.
     */
    @Unauthenticated
    @POST("auth/register")
    suspend fun register(
        @Body body: RegisterRequestDto,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): AuthResponseDto

    @Unauthenticated
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequestDto)
}

/** Used only by [TokenAuthenticator], on a client with no authenticator, synchronously. */
interface RefreshApi {
    @Unauthenticated
    @POST("auth/refresh")
    fun refresh(@Body body: RefreshRequestDto): Call<TokenPairDto>
}
