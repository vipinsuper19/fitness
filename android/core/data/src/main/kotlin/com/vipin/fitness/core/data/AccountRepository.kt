package com.vipin.fitness.core.data

import com.vipin.fitness.core.datastore.ProfileFlagsCache
import com.vipin.fitness.core.network.AccountApi
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.DeleteAccountRequestDto
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DeleteAccountOutcome {
    /** Server erased the account; local data is cleared too. */
    data object Deleted : DeleteAccountOutcome
    data object WrongPassword : DeleteAccountOutcome
    /** Not known whether it went through: retry with the same key. */
    data object Offline : DeleteAccountOutcome
    data object RateLimited : DeleteAccountOutcome
    data object Failed : DeleteAccountOutcome
}

interface AccountRepository {
    /** [idempotencyKey]: one per delete attempt, reused when that attempt is retried. */
    suspend fun deleteAccount(password: String, idempotencyKey: String): DeleteAccountOutcome
}

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val api: AccountApi,
    private val tokens: TokenStore,
    private val json: Json,
    private val cache: ProfileFlagsCache,
    private val userState: Set<@JvmSuppressWildcards UserScopedState> = emptySet(),
) : AccountRepository {
    override suspend fun deleteAccount(password: String, idempotencyKey: String): DeleteAccountOutcome {
        val r = apiCall(json) {
            val res = api.deleteAccount(DeleteAccountRequestDto(password), idempotencyKey)
            if (!res.isSuccessful) throw HttpException(res)
        }
        return when (r) {
            is ApiResult.Success -> {
                clearLocal()
                DeleteAccountOutcome.Deleted
            }
            is ApiResult.Failure -> when (val e = r.error) {
                ApiError.Network -> DeleteAccountOutcome.Offline
                is ApiError.Http -> when {
                    e.code == "INVALID_PASSWORD" -> DeleteAccountOutcome.WrongPassword
                    e.status == 429 -> DeleteAccountOutcome.RateLimited
                    e.code == "IDEMPOTENCY_IN_PROGRESS" || e.status >= 500 -> DeleteAccountOutcome.Offline
                    else -> DeleteAccountOutcome.Failed
                }
                is ApiError.Unexpected -> DeleteAccountOutcome.Failed
            }
        }
    }

    /**
     * Same local wipe as sign-out (tokens, cached flags, every [UserScopedState] including the
     * outbox and workout sessions), minus the logout call: the server already revoked the tokens.
     * Unsynced writes are dropped on purpose; the account they belonged to no longer exists.
     */
    private suspend fun clearLocal() {
        withContext(Dispatchers.IO) { tokens.clear() }
        cache.clear()
        userState.forEach { it.clearUserState() }
    }
}
