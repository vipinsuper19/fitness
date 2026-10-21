package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.ProfileDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT

interface ProfileApi {
    @GET("me/profile")
    suspend fun get(): ProfileDto

    /**
     * Contract `ProfileUpdate`: every field optional, a JSON null clears it, an absent key leaves it.
     * The body is a [JsonObject] built from a ProfileChanges so those two cases stay distinct.
     */
    @PUT("me/profile")
    suspend fun update(
        @Body body: JsonObject,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ProfileDto
}
