package com.vipin.fitness.core.data.achievements

import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.network.AchievementsApi
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.ChallengeJoinRequestDto
import com.vipin.fitness.core.database.badges.SeenBadgeDao
import com.vipin.fitness.core.database.session.WorkoutSessionDao
import kotlinx.serialization.json.Json
import java.time.Clock
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
 * Which earned badges the user has already been shown, for the one-off "Badge earned" sheet
 * (spec §10). Kept in Room per signed-in user, so a badge is celebrated once even across app
 * restarts and screens (badges grid, workout summary). The user's first list is the baseline and
 * shows nothing, so a fresh install or the release backfill doesn't pop a sheet for history.
 * Cleared on sign-out; on sign-in only the signed-in user's rows are kept.
 */
@Singleton
class EarnedBadgeTracker @Inject constructor(private val store: SeenBadgeStore) : UserScopedState {
    /** Earned badges not shown before, now marked seen (none on the user's first list, or when signed out). */
    suspend fun newlyEarned(list: BadgeList): List<Badge> {
        val owner = store.currentOwner() ?: return emptyList()
        val earned = list.items.filter { it.earned }
        val fresh = store.recordEarned(owner, earned.map { it.code }).toSet()
        return earned.filter { it.code in fresh }
    }

    override suspend fun clearUserState() = store.clearAll()

    override suspend fun onSignedIn(userId: String) = store.keepOnly(userId)
}

/** Persistence for [EarnedBadgeTracker]; Room in the app, in-memory in tests. */
interface SeenBadgeStore {
    suspend fun currentOwner(): String?
    /** Marks [codes] seen for [owner]; returns those not seen before (empty on the owner's first call). */
    suspend fun recordEarned(owner: String, codes: List<String>): List<String>
    suspend fun clearAll()
    suspend fun keepOnly(owner: String)
}

@Singleton
class RoomSeenBadgeStore @Inject constructor(
    private val dao: SeenBadgeDao,
    private val sessions: WorkoutSessionDao,
    private val clock: Clock,
) : SeenBadgeStore {
    override suspend fun currentOwner() = sessions.currentUserId()
    override suspend fun recordEarned(owner: String, codes: List<String>) = dao.recordEarned(owner, codes, clock.millis())
    override suspend fun clearAll() = dao.clearAll()
    override suspend fun keepOnly(owner: String) = dao.keepOnly(owner)
}
