package com.vipin.fitness.core.data

import com.vipin.fitness.core.datastore.ProfileFlagsCache
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
    private val cache: ProfileFlagsCache,
    private val zone: ProfileZone? = null,
) : ProfileRepository {
    override suspend fun get(): ApiResult<Profile> {
        val token = zone?.token()
        return apiCall(json) { api.get().toDomain() }.cached(token)
    }

    override suspend fun update(changes: ProfileChanges, idempotencyKey: String): ApiResult<Profile> {
        val token = zone?.token()
        return apiCall(json) { api.update(changes.toJson(), idempotencyKey).toDomain() }.cached(token)
    }

    /**
     * Every profile the server returns refreshes the launch cache. [zoneToken] is taken before the
     * request, so a response that lands after a sign-out/sign-in can't write the old user's zone.
     */
    private suspend fun ApiResult<Profile>.cached(zoneToken: Int?): ApiResult<Profile> = also {
        if (it is ApiResult.Success) cache.save(it.value.onboardingCompleted, it.value.unitSystem)
        if (it is ApiResult.Success && zoneToken != null) zone?.remember(it.value, zoneToken)
    }
}

/** Short user-facing text for a failed API call. */
fun ApiError.userMessage(): String = when (this) {
    ApiError.Network -> "Can't reach the server. Check your connection and try again."
    is ApiError.Http -> message
    is ApiError.Unexpected -> "Something went wrong. Please try again."
}
