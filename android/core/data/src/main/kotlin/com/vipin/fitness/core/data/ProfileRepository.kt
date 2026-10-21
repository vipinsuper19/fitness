package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.ProfileApi
import com.vipin.fitness.core.network.apiCall
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface ProfileRepository {
    suspend fun get(): ApiResult<Profile>
    /** [idempotencyKey]: one per user action, reused if that same save is retried. */
    suspend fun update(changes: ProfileChanges, idempotencyKey: String = newIdempotencyKey()): ApiResult<Profile>
}

@Singleton
class DefaultProfileRepository @Inject constructor(
    private val api: ProfileApi,
    private val json: Json,
) : ProfileRepository {
    override suspend fun get() = apiCall(json) { api.get().toDomain() }

    override suspend fun update(changes: ProfileChanges, idempotencyKey: String): ApiResult<Profile> =
        apiCall(json) { api.update(changes.toJson(), idempotencyKey).toDomain() }
}

/** Short user-facing text for a failed API call. */
fun ApiError.userMessage(): String = when (this) {
    ApiError.Network -> "Can't reach the server. Check your connection and try again."
    is ApiError.Http -> message
    is ApiError.Unexpected -> "Something went wrong. Please try again."
}
