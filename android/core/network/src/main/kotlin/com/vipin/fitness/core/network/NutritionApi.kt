package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.DailyNutritionDto
import com.vipin.fitness.core.network.model.FoodCreateDto
import com.vipin.fitness.core.network.model.FoodDto
import com.vipin.fitness.core.network.model.FoodLogCreateDto
import com.vipin.fitness.core.network.model.FoodLogDto
import com.vipin.fitness.core.network.model.FoodLogListDto
import com.vipin.fitness.core.network.model.FoodPageDto
import com.vipin.fitness.core.network.model.WaterLogCreateDto
import com.vipin.fitness.core.network.model.WaterLogDto
import com.vipin.fitness.core.network.model.WaterLogListDto
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

/** Nutrition endpoints (contract tag `nutrition`). Dates are local `yyyy-MM-dd` in the user's timezone. */
interface NutritionApi {
    @GET("foods/search")
    suspend fun searchFoods(
        @Query("query") query: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): FoodPageDto

    @POST("foods")
    suspend fun createFood(@Body body: FoodCreateDto, @Header("Idempotency-Key") idempotencyKey: String): FoodDto

    @GET("food-logs")
    suspend fun foodLogs(@Query("date") date: String): FoodLogListDto

    @POST("food-logs")
    suspend fun createFoodLog(@Body body: FoodLogCreateDto, @Header("Idempotency-Key") idempotencyKey: String): FoodLogDto

    /** Contract `FoodLogUpdate`: absent key = unchanged; `nutrientsOverride: null` restores computed values. */
    @PATCH("food-logs/{id}")
    suspend fun updateFoodLog(
        @Path("id") id: String,
        @Body body: JsonObject,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): FoodLogDto

    @DELETE("food-logs/{id}")
    suspend fun deleteFoodLog(@Path("id") id: String, @Header("Idempotency-Key") idempotencyKey: String): Response<Unit>

    @GET("water-logs")
    suspend fun waterLogs(@Query("date") date: String): WaterLogListDto

    @POST("water-logs")
    suspend fun createWaterLog(@Body body: WaterLogCreateDto, @Header("Idempotency-Key") idempotencyKey: String): WaterLogDto

    @PATCH("water-logs/{id}")
    suspend fun updateWaterLog(
        @Path("id") id: String,
        @Body body: WaterLogCreateDto,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): WaterLogDto

    @DELETE("water-logs/{id}")
    suspend fun deleteWaterLog(@Path("id") id: String, @Header("Idempotency-Key") idempotencyKey: String): Response<Unit>

    @GET("nutrition/daily")
    suspend fun daily(@Query("date") date: String): DailyNutritionDto
}
