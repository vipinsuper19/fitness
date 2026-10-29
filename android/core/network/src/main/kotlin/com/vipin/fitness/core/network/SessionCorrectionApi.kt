package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.SessionCompletionUpdateDto
import com.vipin.fitness.core.network.model.SessionCorrectionResultDto
import com.vipin.fitness.core.network.model.SetLogUpsertDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path

/** Corrections to a completed session (#63, docs/specs/session-correction.md). */
interface SessionCorrectionApi {
    @PUT("workout-sessions/{sessionId}/corrections/sets/{clientSetId}")
    suspend fun upsertSet(
        @Path("sessionId") sessionId: String,
        @Path("clientSetId") clientSetId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: SetLogUpsertDto,
    ): SessionCorrectionResultDto

    @HTTP(method = "DELETE", path = "workout-sessions/{sessionId}/corrections/sets/{clientSetId}", hasBody = false)
    suspend fun deleteSet(
        @Path("sessionId") sessionId: String,
        @Path("clientSetId") clientSetId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): SessionCorrectionResultDto

    @PATCH("workout-sessions/{sessionId}/completion")
    suspend fun correctCompletion(
        @Path("sessionId") sessionId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: SessionCompletionUpdateDto,
    ): SessionCorrectionResultDto

    /** Discard a session, completed or not: 204, no body. */
    @DELETE("workout-sessions/{sessionId}")
    suspend fun discard(@Path("sessionId") sessionId: String): Response<Unit>
}
