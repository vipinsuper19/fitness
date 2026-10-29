package com.vipin.fitness.core.data.history

import com.vipin.fitness.core.data.XpBalance
import com.vipin.fitness.core.data.achievements.Badge
import com.vipin.fitness.core.data.achievements.toDomain
import com.vipin.fitness.core.data.toDomain
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.SessionCorrectionApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.SessionCompletionUpdateDto
import com.vipin.fitness.core.network.model.SessionCorrectionResultDto
import com.vipin.fitness.core.network.model.SetLogUpsertDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What a correction did (SessionCorrectionResult): the corrected session, the XP change and any badges it earned. */
data class SessionCorrectionResult(
    val session: PastSession,
    val xpDelta: Int,
    val xpBalance: XpBalance,
    val newBadges: List<Badge>,
)

/** Error codes from docs/specs/session-correction.md. */
object CorrectionErrors {
    const val WINDOW_CLOSED = "CORRECTION_WINDOW_CLOSED"
    const val SESSION_EMPTY = "SESSION_EMPTY"
    const val NOT_COMPLETED = "SESSION_NOT_COMPLETED"
    const val SET_NOT_FOUND = "SET_NOT_FOUND"
}

/**
 * Corrections are allowed while the session's completedAt local date (profile zone) is today or
 * yesterday. The server is the authority (409 CORRECTION_WINDOW_CLOSED); this only hides the actions.
 */
object CorrectionWindow {
    fun isOpen(completedAt: Instant?, zone: ZoneId, today: LocalDate): Boolean {
        val day = completedAt?.atZone(zone)?.toLocalDate() ?: return false
        return day == today || day == today.minusDays(1)
    }

    /**
     * A new completedAt must be on the same local date as [current], no later than it, and after
     * [startedAt] (else the server answers 400).
     */
    fun validCompletedAt(new: Instant, current: Instant, startedAt: Instant, zone: ZoneId): Boolean =
        !new.isAfter(current) && new.isAfter(startedAt) &&
            new.atZone(zone).toLocalDate() == current.atZone(zone).toLocalDate()
}

/** Something changed a past session; Home re-fetches XP and the history list reloads. */
sealed interface SessionChange {
    val sessionId: String
    data class Corrected(override val sessionId: String) : SessionChange
    data class Discarded(override val sessionId: String) : SessionChange
}

@Singleton
class SessionChanges @Inject constructor() {
    private val _events = MutableSharedFlow<SessionChange>(extraBufferCapacity = 8)
    val events: SharedFlow<SessionChange> = _events.asSharedFlow()
    fun emit(change: SessionChange) { _events.tryEmit(change) }
}

interface SessionCorrectionRepository {
    /** Edit a set in place, or add one, by its clientSetId. */
    suspend fun upsertSet(sessionId: String, set: PastSet): ApiResult<SessionCorrectionResult>
    suspend fun deleteSet(sessionId: String, clientSetId: String): ApiResult<SessionCorrectionResult>
    /** Only non-null fields change. */
    suspend fun correctCompletion(sessionId: String, completedAt: Instant? = null, perceivedDifficulty: Int? = null, notes: String? = null): ApiResult<SessionCorrectionResult>
    /** Removes the session; the server writes back its net XP. Callers re-fetch XP. */
    suspend fun discard(sessionId: String): ApiResult<Unit>
}

@Singleton
class DefaultSessionCorrectionRepository @Inject constructor(
    private val api: SessionCorrectionApi,
    private val changes: SessionChanges,
    private val json: Json,
) : SessionCorrectionRepository {
    override suspend fun upsertSet(sessionId: String, set: PastSet) = correct(sessionId) {
        api.upsertSet(sessionId, set.clientSetId, newKey(), set.toUpsert())
    }

    override suspend fun deleteSet(sessionId: String, clientSetId: String) = correct(sessionId) {
        api.deleteSet(sessionId, clientSetId, newKey())
    }

    override suspend fun correctCompletion(sessionId: String, completedAt: Instant?, perceivedDifficulty: Int?, notes: String?) = correct(sessionId) {
        api.correctCompletion(sessionId, newKey(), SessionCompletionUpdateDto(completedAt?.toString(), perceivedDifficulty, notes))
    }

    override suspend fun discard(sessionId: String): ApiResult<Unit> {
        val r = apiCall(json) {
            val res = api.discard(sessionId)
            if (!res.isSuccessful && res.code() != 404) throw HttpException(res)
        }
        // 404: already gone (e.g. discarded on another device), same outcome.
        if (r is ApiResult.Success) changes.emit(SessionChange.Discarded(sessionId))
        return r
    }

    private suspend fun correct(sessionId: String, call: suspend () -> SessionCorrectionResultDto): ApiResult<SessionCorrectionResult> {
        val r = apiCall(json) { call().toDomain() }
        if (r is ApiResult.Success) changes.emit(SessionChange.Corrected(sessionId))
        return r
    }

    private fun newKey() = UUID.randomUUID().toString()
}

internal fun PastSet.toUpsert() = SetLogUpsertDto(
    clientSetId = clientSetId, exerciseId = exerciseId, setIndex = setIndex, completed = completed,
    reps = reps, weightKg = weightKg, durationSeconds = durationSeconds, distanceM = distanceM,
)

internal fun SessionCorrectionResultDto.toDomain() = SessionCorrectionResult(
    session = session.toDomain(),
    xpDelta = xpDelta,
    xpBalance = xpBalance.toDomain(),
    newBadges = newBadges.orEmpty().map { it.toDomain() },
)
