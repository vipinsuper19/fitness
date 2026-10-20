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

/** Auth endpoints from the T5 draft. Paths are relative to `.../api/v1/`. */
interface AuthApi {
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
