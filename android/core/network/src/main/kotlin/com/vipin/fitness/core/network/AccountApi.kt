package com.vipin.fitness.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.Header

/** Contract `DeleteAccountRequest`. */
@Serializable
data class DeleteAccountRequestDto(val password: String)

/** Account endpoints (contract tag `me`). */
interface AccountApi {
    /** 204 on success; 403 `INVALID_PASSWORD`. Revokes every token server-side. */
    @HTTP(method = "DELETE", path = "me", hasBody = true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequestDto, @Header("Idempotency-Key") idempotencyKey: String): Response<Unit>
}
