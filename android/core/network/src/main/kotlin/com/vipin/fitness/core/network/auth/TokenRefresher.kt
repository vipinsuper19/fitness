package com.vipin.fitness.core.network.auth

import com.vipin.fitness.core.network.model.RefreshRequestDto
import java.io.IOException
import javax.inject.Inject

sealed interface RefreshResult {
    data class Success(val tokens: AuthTokens) : RefreshResult
    /** 401 from /auth/refresh (invalid, expired or reused): the session is over. */
    data object Rejected : RefreshResult
    /** Network error, 5xx, 429...: keep the session, let this request fail. */
    data object Failed : RefreshResult
}

fun interface TokenRefresher {
    fun refresh(refreshToken: String): RefreshResult
}

class RetrofitTokenRefresher @Inject constructor(
    private val api: RefreshApi,
    private val clock: () -> Long,
) : TokenRefresher {
    override fun refresh(refreshToken: String): RefreshResult = try {
        val response = api.refresh(RefreshRequestDto(refreshToken)).execute()
        val body = response.body()
        when {
            response.isSuccessful && body != null -> RefreshResult.Success(body.toAuthTokens(clock()))
            response.code() == 401 -> RefreshResult.Rejected
            else -> RefreshResult.Failed
        }
    } catch (e: IOException) {
        RefreshResult.Failed
    } catch (e: RuntimeException) { // e.g. malformed body
        RefreshResult.Failed
    }
}
