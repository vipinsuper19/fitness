package com.vipin.fitness.core.network

import kotlinx.serialization.Serializable
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

/** Contract `Goal`. type/status stay strings so new server values don't break parsing. */
@Serializable
data class GoalDto(
    val id: String,
    val type: String,
    val status: String,
    val targetValue: Double? = null,
    val startValue: Double? = null,
    val targetDate: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class GoalListDto(val items: List<GoalDto>)

/** Goals (contract tag `goals`). Writes take an Idempotency-Key. PATCH bodies are built as JSON so null can clear. */
interface GoalsApi {
    @GET("goals")
    suspend fun list(@Query("status") status: String? = null): GoalListDto

    @POST("goals")
    suspend fun create(@Body body: JsonObject, @Header("Idempotency-Key") idempotencyKey: String): GoalDto

    @PATCH("goals/{goalId}")
    suspend fun update(@Path("goalId") id: String, @Body body: JsonObject, @Header("Idempotency-Key") idempotencyKey: String): GoalDto

    @DELETE("goals/{goalId}")
    suspend fun delete(@Path("goalId") id: String, @Header("Idempotency-Key") idempotencyKey: String): Response<Unit>
}
