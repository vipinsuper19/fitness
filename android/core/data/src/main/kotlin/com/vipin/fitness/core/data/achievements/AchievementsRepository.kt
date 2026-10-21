package com.vipin.fitness.core.data.achievements

import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.network.AchievementsApi
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.ChallengeJoinRequestDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Badges (read-only; awarded by the server) and challenges (join / leave). Online only in v1:
 * join and leave need the network. Each join or leave takes an [idempotencyKey]: one per user
 * action, reused when that same action is retried.
 */
interface AchievementsRepository {
    suspend fun badges(): ApiResult<BadgeList>
    suspend fun templates(): ApiResult<List<ChallengeTemplate>>
    suspend fun challenges(status: ChallengeStatus? = null, cursor: String? = null): ApiResult<ChallengePage>
    suspend fun join(templateCode: String, target: Int, idempotencyKey: String = newIdempotencyKey()): ApiResult<Challenge>
    suspend fun leave(id: String, idempotencyKey: String = newIdempotencyKey()): ApiResult<Challenge>
}

/** Error codes from contract #43 that the UI branches on. */
object AchievementErrors {
    const val ALREADY_JOINED = "CHALLENGE_ALREADY_JOINED"
    const val LIMIT_REACHED = "CHALLENGE_LIMIT_REACHED"
    const val NOT_ACTIVE = "CHALLENGE_NOT_ACTIVE"
}

@Singleton
class DefaultAchievementsRepository @Inject constructor(
    private val api: AchievementsApi,
    private val json: Json,
) : AchievementsRepository {
    override suspend fun badges() = apiCall(json) { api.badges().toDomain() }

    override suspend fun templates() = apiCall(json) { api.templates().items.map { it.toDomain() } }

    override suspend fun challenges(status: ChallengeStatus?, cursor: String?) = apiCall(json) {
        // UNKNOWN has no wire value: ask for everything rather than send "".
        val page = api.challenges(status?.takeIf { it != ChallengeStatus.UNKNOWN }?.wire, cursor, PAGE_SIZE)
        ChallengePage(page.items.map { it.toDomain() }, page.nextCursor)
    }

    override suspend fun join(templateCode: String, target: Int, idempotencyKey: String) =
        apiCall(json) { api.join(ChallengeJoinRequestDto(templateCode, target), idempotencyKey).toDomain() }

    override suspend fun leave(id: String, idempotencyKey: String) = apiCall(json) { api.leave(id, idempotencyKey).toDomain() }

    companion object {
        const val PAGE_SIZE = 20
    }
}

/**
 * Which earned badges the user has already been shown, for the one-off "Badge earned" sheet.
 * The first list seen after sign-in (or app start) is the baseline and shows nothing, so a fresh
 * install or the release backfill doesn't pop a sheet for history. Kept in memory per user.
 */
@Singleton
class EarnedBadgeTracker @Inject constructor() : UserScopedState {
    private val mutex = Mutex()
    private var seen: Set<String>? = null

    /** Badges earned since the last call (none on the first call). */
    suspend fun newlyEarned(list: BadgeList): List<Badge> = mutex.withLock {
        val earned = list.items.filter { it.earned }
        val before = seen
        seen = (before.orEmpty()) + earned.map { it.code }
        if (before == null) emptyList() else earned.filter { it.code !in before }
    }

    override suspend fun clearUserState() = mutex.withLock { seen = null }
}
