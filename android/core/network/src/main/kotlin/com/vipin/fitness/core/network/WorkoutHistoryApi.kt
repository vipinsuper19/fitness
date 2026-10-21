package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.SessionHistoryPageDto
import com.vipin.fitness.core.network.model.SessionDetailDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Read-only past sessions (contract: listWorkoutHistory, getWorkoutSession). */
interface WorkoutHistoryApi {
    @GET("workout-sessions/history")
    suspend fun history(@Query("cursor") cursor: String? = null, @Query("limit") limit: Int? = null): SessionHistoryPageDto

    @GET("workout-sessions/{sessionId}")
    suspend fun session(@Path("sessionId") id: String): SessionDetailDto
}
