package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.MacroSuggestionDto
import retrofit2.http.GET

/** Read-only, never writes, so no Idempotency-Key. Applying goes through PUT /me/profile. */
interface MacroSuggestionApi {
    @GET("me/macro-suggestion")
    suspend fun get(): MacroSuggestionDto
}
