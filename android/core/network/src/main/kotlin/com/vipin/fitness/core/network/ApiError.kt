package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.ErrorDto
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/** What the UI and repositories see instead of raw Retrofit/OkHttp exceptions. */
sealed interface ApiError {
    /** Server answered with an error body. [code] is the contract's stable code, e.g. EMAIL_TAKEN. */
    data class Http(val status: Int, val code: String, val message: String, val requestId: String?) : ApiError
    data object Network : ApiError
    data class Unexpected(val cause: Throwable) : ApiError
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

/** Runs a Retrofit suspend call and maps failures. Cancellation is rethrown. */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
} catch (e: HttpException) {
    ApiResult.Failure(e.toApiError(json))
} catch (e: IOException) {
    ApiResult.Failure(ApiError.Network)
} catch (e: Exception) {
    ApiResult.Failure(ApiError.Unexpected(e))
}

internal fun HttpException.toApiError(json: Json): ApiError.Http {
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val dto = raw?.let { runCatching { json.decodeFromString(ErrorDto.serializer(), it) }.getOrNull() }
    return ApiError.Http(
        status = code(),
        code = dto?.code ?: "HTTP_${code()}",
        message = dto?.message ?: message(),
        requestId = dto?.requestId ?: response()?.headers()?.get("X-Request-Id"),
    )
}
