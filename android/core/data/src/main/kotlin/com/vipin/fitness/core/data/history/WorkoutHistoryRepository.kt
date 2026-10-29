package com.vipin.fitness.core.data.history

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.WorkoutHistoryApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.SessionDetailDto
import com.vipin.fitness.core.network.model.SetLogDto
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A completed session from the server. [sets] is empty in list rows if the server omits them. */
data class PastSession(
    val id: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val activeSeconds: Int,
    val perceivedDifficulty: Int?,
    val notes: String?,
    val sets: List<PastSet>,
) {
    val completedSets get() = sets.count { it.completed }
    val exerciseIds get() = sets.map { it.exerciseId }.distinct()
}

/** [clientSetId] is the key session corrections (#57) will edit by. */
data class PastSet(
    val clientSetId: String,
    val exerciseId: String,
    val setIndex: Int,
    val completed: Boolean,
    val reps: Int?,
    val weightKg: Double?,
    val durationSeconds: Int?,
    val distanceM: Double?,
)

data class SessionHistoryPage(val items: List<PastSession>, val nextCursor: String?)

/**
 * Read-only for now. Corrections and discard (#57) will add write calls here; a discarded
 * session answers 404 on GET, which the detail screen already treats as "removed".
 */
interface WorkoutHistoryRepository {
    suspend fun history(cursor: String? = null): ApiResult<SessionHistoryPage>
    suspend fun session(id: String): ApiResult<PastSession>
}

@Singleton
class DefaultWorkoutHistoryRepository @Inject constructor(
    private val api: WorkoutHistoryApi,
    private val json: Json,
) : WorkoutHistoryRepository {
    override suspend fun history(cursor: String?) = apiCall(json) {
        val page = api.history(cursor, PAGE_SIZE)
        SessionHistoryPage(page.items.map { it.toDomain() }, page.nextCursor)
    }

    override suspend fun session(id: String) = apiCall(json) { api.session(id).toDomain() }

    companion object { const val PAGE_SIZE = 20 }
}

internal fun SessionDetailDto.toDomain() = PastSession(
    id = id,
    startedAt = Instant.parse(startedAt),
    completedAt = completedAt?.let(Instant::parse),
    activeSeconds = activeSeconds,
    perceivedDifficulty = perceivedDifficulty,
    notes = notes,
    sets = sets.map { it.toDomain() }.sortedWith(compareBy({ it.exerciseId }, { it.setIndex })),
)

private fun SetLogDto.toDomain() = PastSet(clientSetId, exerciseId, setIndex, completed, reps, weightKg, durationSeconds, distanceM)
