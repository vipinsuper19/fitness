package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.AccountApi
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.DeleteAccountRequestDto
import com.vipin.fitness.core.network.apiCall
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account-level actions from Profile (contract tag `me`). Shape agreed with QA (#55). Deleting
 * only calls the server; the caller then signs out locally (SessionRepository.signOut), which
 * wipes tokens, cached flags and every UserScopedState (outbox, sessions, caches).
 */
interface AccountRepository {
    /** DELETE /me {password} with [idempotencyKey]: 204 → Success. 403 `INVALID_PASSWORD` on a wrong password. */
    suspend fun deleteAccount(password: String, idempotencyKey: String): ApiResult<Unit>
    /** GET /me/export → the raw JSON body. */
    suspend fun exportJson(): ApiResult<String>
}

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val api: AccountApi,
    private val json: Json,
) : AccountRepository {
    override suspend fun deleteAccount(password: String, idempotencyKey: String) = apiCall(json) {
        val res = api.deleteAccount(DeleteAccountRequestDto(password), idempotencyKey)
        if (!res.isSuccessful) throw HttpException(res)
    }

    override suspend fun exportJson() = apiCall(json) { api.export().string() }
}
