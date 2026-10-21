package com.vipin.fitness.core.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Replays queued offline writes (pending_writes) as stored: method, path relative to /api/v1/,
 * JSON body and the Idempotency-Key captured when the user acted. Auth is added by the client.
 */
interface OutboxApi {
    @POST("{path}")
    suspend fun post(
        @Path(value = "path", encoded = true) path: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @PATCH("{path}")
    suspend fun patch(
        @Path(value = "path", encoded = true) path: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}
