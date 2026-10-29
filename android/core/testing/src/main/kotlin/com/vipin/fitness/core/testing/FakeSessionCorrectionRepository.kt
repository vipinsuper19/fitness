package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.XpBalance
import com.vipin.fitness.core.data.achievements.Badge
import com.vipin.fitness.core.data.history.PastSession
import com.vipin.fitness.core.data.history.PastSet
import com.vipin.fitness.core.data.history.SessionChange
import com.vipin.fitness.core.data.history.SessionChanges
import com.vipin.fitness.core.data.history.SessionCorrectionRepository
import com.vipin.fitness.core.data.history.SessionCorrectionResult
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import java.time.Instant

fun windowClosedFailure() = ApiResult.Failure(ApiError.Http(409, "CORRECTION_WINDOW_CLOSED", "closed", null))

/**
 * In-memory corrections. By default each call succeeds and applies the change to [session],
 * answering with [xpDelta] and [newBadges]; set [failure] to answer with an error instead.
 * Successful calls are announced on [changes], like the real repository.
 */
class FakeSessionCorrectionRepository(val changes: SessionChanges = SessionChanges()) : SessionCorrectionRepository {
    var session: PastSession = testPastSession("s1")
    var xpDelta = 0
    var newBadges: List<Badge> = emptyList()
    var failure: ApiResult.Failure? = null
    var discardFailure: ApiResult.Failure? = null
    val calls = mutableListOf<String>()

    override suspend fun upsertSet(sessionId: String, set: PastSet): ApiResult<SessionCorrectionResult> = answer("put:${set.clientSetId}", sessionId) {
        val others = session.sets.filter { it.clientSetId != set.clientSetId }
        session.copy(sets = (others + set).sortedWith(compareBy({ it.exerciseId }, { it.setIndex })))
    }

    override suspend fun deleteSet(sessionId: String, clientSetId: String) = answer("delete:$clientSetId", sessionId) {
        session.copy(sets = session.sets.filter { it.clientSetId != clientSetId })
    }

    override suspend fun correctCompletion(sessionId: String, completedAt: Instant?, perceivedDifficulty: Int?, notes: String?) =
        answer("completion:$completedAt:$perceivedDifficulty:$notes", sessionId) {
            session.copy(
                completedAt = completedAt ?: session.completedAt,
                perceivedDifficulty = perceivedDifficulty ?: session.perceivedDifficulty,
                notes = notes ?: session.notes,
            )
        }

    override suspend fun discard(sessionId: String): ApiResult<Unit> {
        calls += "discard"
        discardFailure?.let { return it }
        changes.emit(SessionChange.Discarded(sessionId))
        return ApiResult.Success(Unit)
    }

    private fun answer(call: String, sessionId: String, change: () -> PastSession): ApiResult<SessionCorrectionResult> {
        calls += call
        failure?.let { return it }
        session = change()
        changes.emit(SessionChange.Corrected(sessionId))
        return ApiResult.Success(SessionCorrectionResult(session, xpDelta, XpBalance(100 + xpDelta, 1, 100 + xpDelta, 200), newBadges))
    }
}
