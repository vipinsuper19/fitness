package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.NotificationPreferencesDto
import com.vipin.fitness.core.network.model.NotificationPreferencesUpdateDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH

/** docs/specs/notification-preferences.md. Storage only: nothing here sends or registers for notifications. */
interface NotificationPreferencesApi {
    @GET("me/notification-preferences")
    suspend fun get(): NotificationPreferencesDto

    /** Partial merge; returns the full merged result. */
    @PATCH("me/notification-preferences")
    suspend fun update(
        @Body body: NotificationPreferencesUpdateDto,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): NotificationPreferencesDto
}
