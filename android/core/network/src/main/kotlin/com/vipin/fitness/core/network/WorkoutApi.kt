package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.ExerciseDto
import com.vipin.fitness.core.network.model.ExercisePageDto
import com.vipin.fitness.core.network.model.PlanGenerateRequestDto
import com.vipin.fitness.core.network.model.WorkoutPlanDraftDto
import com.vipin.fitness.core.network.model.WorkoutPlanDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface WorkoutApi {
    @GET("exercises")
    suspend fun exercises(
        @Query("query") query: String? = null,
        @Query("muscle") muscle: String? = null,
        @Query("equipment") equipment: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): ExercisePageDto

    @GET("exercises/{id}")
    suspend fun exercise(@Path("id") id: String): ExerciseDto

    /** Never persisted; same input gives the same draft. */
    @POST("workout-plans/generate")
    suspend fun generate(@Body body: PlanGenerateRequestDto): WorkoutPlanDraftDto

    @POST("workout-plans")
    suspend fun create(@Body body: WorkoutPlanDraftDto, @Header("Idempotency-Key") idempotencyKey: String): WorkoutPlanDto

    /** 204 when there's no active plan, so the raw [Response] is returned. */
    @GET("workout-plans/active")
    suspend fun active(): Response<WorkoutPlanDto>

    @POST("workout-plans/{id}/activate")
    suspend fun activate(@Path("id") id: String, @Header("Idempotency-Key") idempotencyKey: String): WorkoutPlanDto
}
