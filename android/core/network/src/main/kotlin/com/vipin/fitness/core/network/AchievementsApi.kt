package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.BadgeListDto
import com.vipin.fitness.core.network.model.ChallengeDto
import com.vipin.fitness.core.network.model.ChallengeJoinRequestDto
import com.vipin.fitness.core.network.model.ChallengePageDto
import com.vipin.fitness.core.network.model.ChallengeTemplateListDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Badges and challenges (contract tags `badges` and `challenges`, #43). */
interface AchievementsApi {
    @GET("badges")
    suspend fun badges(): BadgeListDto

    @GET("challenges/templates")
    suspend fun templates(): ChallengeTemplateListDto

    @GET("challenges")
    suspend fun challenges(
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): ChallengePageDto

    @POST("challenges")
    suspend fun join(@Body body: ChallengeJoinRequestDto, @Header("Idempotency-Key") idempotencyKey: String): ChallengeDto

    @POST("challenges/{id}/leave")
    suspend fun leave(@Path("id") id: String, @Header("Idempotency-Key") idempotencyKey: String?): ChallengeDto
}
