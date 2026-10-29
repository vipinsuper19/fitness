package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.history.PastSession
import com.vipin.fitness.core.data.history.PastSet
import com.vipin.fitness.core.data.history.SessionHistoryPage
import com.vipin.fitness.core.data.history.WorkoutHistoryRepository
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

fun testPastSession(id: String, sets: List<PastSet> = listOf(testPastSet("e1", 0)), startedAt: String = "2026-10-20T06:00:00Z") = PastSession(
    id = id, startedAt = Instant.parse(startedAt), completedAt = Instant.parse(startedAt).plusSeconds(1800),
    activeSeconds = 1500, perceivedDifficulty = 3, notes = null, sets = sets,
)

fun testPastSet(exerciseId: String, index: Int, reps: Int? = 10, weightKg: Double? = 20.0, completed: Boolean = true) =
    PastSet("cs-$exerciseId-$index", exerciseId, index, completed, reps, weightKg, null, null)

class FakeWorkoutHistoryRepository : WorkoutHistoryRepository {
    /** Pages by cursor (null = first page). */
    var pages: (String?) -> ApiResult<SessionHistoryPage> = { ApiResult.Success(SessionHistoryPage(emptyList(), null)) }
    var sessionResult: (String) -> ApiResult<PastSession> = { ApiResult.Success(testPastSession(it)) }
    val historyCalls = mutableListOf<String?>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun history(cursor: String?): ApiResult<SessionHistoryPage> {
        historyCalls += cursor
        gate?.await()
        return pages(cursor)
    }

    override suspend fun session(id: String): ApiResult<PastSession> {
        gate?.await()
        return sessionResult(id)
    }
}
