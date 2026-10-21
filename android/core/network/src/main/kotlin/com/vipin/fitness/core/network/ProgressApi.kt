package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.ActivePlanDto
import com.vipin.fitness.core.network.model.BodyMeasurementDto
import com.vipin.fitness.core.network.model.BodyMeasurementPageDto
import com.vipin.fitness.core.network.model.ProgressSummaryDto
import com.vipin.fitness.core.network.model.XpBalanceDto
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Progress endpoints (contract tags `progress` and `xp`), plus the active-plan read Home needs. */
interface ProgressApi {
    @GET("body-measurements")
    suspend fun measurements(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): BodyMeasurementPageDto

    /** Contract `BodyMeasurementCreate`: `date` plus at least one measurement. */
    @POST("body-measurements")
    suspend fun createMeasurement(@Body body: JsonObject, @Header("Idempotency-Key") idempotencyKey: String): BodyMeasurementDto

    /** Contract `BodyMeasurementUpdate`: absent key = unchanged, `null` clears; `{}` is a 400. */
    @PATCH("body-measurements/{id}")
    suspend fun updateMeasurement(
        @Path("id") id: String,
        @Body body: JsonObject,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): BodyMeasurementDto

    @DELETE("body-measurements/{id}")
    suspend fun deleteMeasurement(@Path("id") id: String, @Header("Idempotency-Key") idempotencyKey: String): Response<Unit>

    @GET("progress/summary")
    suspend fun summary(@Query("from") from: String, @Query("to") to: String): ProgressSummaryDto

    @GET("xp/balance")
    suspend fun xpBalance(): XpBalanceDto

    /** 204 when there is no active plan. */
    @GET("workout-plans/active")
    suspend fun activePlan(): Response<ActivePlanDto>
}
