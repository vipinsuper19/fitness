package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.ErrorDto
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/** What the UI and repositories see instead of raw Retrofit/OkHttp exceptions. */
sealed interface ApiError {
    /** Server answered with an error body. [code] is the contract's stable code, e.g. EMAIL_TAKEN. */
    data class Http(
        val status: Int,
        val code: String,
        val message: String,
        val requestId: String?,
        /**
         * From a 429/503 `Retry-After` header (seconds or HTTP date), in ms from now. A 429 without a
         * usable header gets [DEFAULT_RETRY_AFTER_MS]; otherwise null if absent.
         */
        val retryAfterMs: Long? = null,
    ) : ApiError
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
        retryAfterMs = retryAfterFor(code(), response()?.headers()?.get("Retry-After"), System.currentTimeMillis()),
    )
}

/**
 * True when the same request (same Idempotency-Key) may succeed later: offline, 408, 429, 5xx or a
 * concurrent duplicate. Callers keep the write and its key instead of dropping or re-keying it.
 */
val ApiError.isRetryable: Boolean
    get() = when (this) {
        ApiError.Network -> true
        is ApiError.Unexpected -> false
        is ApiError.Http -> status == 408 || status == 429 || status >= 500 || code == "IDEMPOTENCY_IN_PROGRESS"
    }

/** Wait after a 429 whose `Retry-After` is missing or unparseable (never 0 ms: that would hammer the server). */
const val DEFAULT_RETRY_AFTER_MS = 30_000L

/** [status]'s wait: the header if usable; a 429 without one waits [DEFAULT_RETRY_AFTER_MS]; others null. */
fun retryAfterFor(status: Int, header: String?, nowMs: Long): Long? =
    parseRetryAfter(header, nowMs) ?: DEFAULT_RETRY_AFTER_MS.takeIf { status == 429 }

/** `Retry-After` as delay-seconds or an HTTP date, in ms from [nowMs] (never negative); null if missing or bad. */
fun parseRetryAfter(value: String?, nowMs: Long): Long? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    v.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
    return runCatching {
        val at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (at - nowMs).coerceAtLeast(0)
    }.getOrNull()
}
