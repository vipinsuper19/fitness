package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.NutritionSummaryDto
import retrofit2.http.GET
import retrofit2.http.Query

/** Read-only; no Idempotency-Key. Dates are local `yyyy-MM-dd` in the profile timezone. */
interface NutritionSummaryApi {
    @GET("nutrition/summary")
    suspend fun summary(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("bucket") bucket: String,
    ): NutritionSummaryDto
}
