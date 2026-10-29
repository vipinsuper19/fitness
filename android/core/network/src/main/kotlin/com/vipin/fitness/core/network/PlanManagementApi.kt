package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.PlanActivateDto
import com.vipin.fitness.core.network.model.PlanRenameDto
import com.vipin.fitness.core.network.model.PlanSummaryPageDto
import com.vipin.fitness.core.network.model.WorkoutPlanDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Plan management: list, pause/resume/restart, archive/unarchive, rename. */
interface PlanManagementApi {
    @GET("workout-plans")
    suspend fun list(
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): PlanSummaryPageDto

    /** Pause. 204, also when the plan isn't active. */
    @POST("workout-plans/{id}/deactivate")
    suspend fun deactivate(@Path("id") id: String, @Header("Idempotency-Key") key: String): Response<Unit>

    /** Resume (`resume: true`) continues the week count. */
    @POST("workout-plans/{id}/activate")
    suspend fun activate(@Path("id") id: String, @Header("Idempotency-Key") key: String, @Body body: PlanActivateDto): WorkoutPlanDto

    /** Restart / switch: no body, so the plan starts at week 1. */
    @POST("workout-plans/{id}/activate")
    suspend fun restart(@Path("id") id: String, @Header("Idempotency-Key") key: String): WorkoutPlanDto

    /** Archive (soft delete). 204, also when already archived. */
    @DELETE("workout-plans/{id}")
    suspend fun archive(@Path("id") id: String, @Header("Idempotency-Key") key: String): Response<Unit>

    /** Always 200 with the plan, archived or not. */
    @POST("workout-plans/{id}/unarchive")
    suspend fun unarchive(@Path("id") id: String, @Header("Idempotency-Key") key: String): WorkoutPlanDto

    @PUT("workout-plans/{id}/name")
    suspend fun rename(@Path("id") id: String, @Header("Idempotency-Key") key: String, @Body body: PlanRenameDto): WorkoutPlanDto
}
