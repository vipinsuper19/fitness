package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.ExerciseProgressDto
import com.vipin.fitness.core.network.model.ExerciseProgressPageDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Strength progress (#72). Read-only; no Idempotency-Key. Dates are local `yyyy-MM-dd` in the profile timezone. */
interface StrengthProgressApi {
    @GET("progress/exercises")
    suspend fun exercises(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): ExerciseProgressPageDto

    @GET("progress/exercises/{exerciseId}")
    suspend fun exercise(
        @Path("exerciseId") exerciseId: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("bucket") bucket: String,
    ): ExerciseProgressDto
}
