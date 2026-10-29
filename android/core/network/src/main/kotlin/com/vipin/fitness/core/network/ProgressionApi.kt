package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.ProgressionSuggestionsDto
import com.vipin.fitness.core.network.model.WorkoutPlanDto
import com.vipin.fitness.core.network.model.WorkoutPlanUpdateDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path

/** Adaptive progression: read suggestions, apply accepted ones as one plan version. */
interface ProgressionApi {
    /** Latest version. */
    @GET("workout-plans/{id}")
    suspend fun plan(@Path("id") id: String): WorkoutPlanDto

    /** Read-only; never applies anything. */
    @GET("workout-plans/{id}/progression-suggestions")
    suspend fun suggestions(@Path("id") id: String): ProgressionSuggestionsDto

    @PATCH("workout-plans/{id}")
    suspend fun update(@Path("id") id: String, @Header("Idempotency-Key") key: String, @Body body: WorkoutPlanUpdateDto): WorkoutPlanDto
}
