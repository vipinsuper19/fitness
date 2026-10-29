package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.achievements.AchievementErrors
import com.vipin.fitness.core.data.achievements.AchievementsRepository
import com.vipin.fitness.core.data.achievements.Badge
import com.vipin.fitness.core.data.achievements.BadgeFamily
import com.vipin.fitness.core.data.achievements.BadgeList
import com.vipin.fitness.core.data.achievements.BadgeProgress
import com.vipin.fitness.core.data.achievements.Challenge
import com.vipin.fitness.core.data.achievements.ChallengePage
import com.vipin.fitness.core.data.achievements.ChallengeStatus
import com.vipin.fitness.core.data.achievements.ChallengeTemplate
import com.vipin.fitness.core.data.achievements.ChallengeType
import com.vipin.fitness.core.data.achievements.TargetOption
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun testBadge(code: String, earnedAt: Instant? = null, progress: BadgeProgress? = BadgeProgress(0, 1), family: BadgeFamily = BadgeFamily.WORKOUT) =
    Badge(code, "Badge $code", "Rule for $code", family, 10, earnedAt, if (earnedAt != null) null else progress)

fun testTemplate(code: String = "WEEKLY_WORKOUTS", type: ChallengeType = ChallengeType.WEEKLY, targets: List<Int> = listOf(2, 3, 4)) =
    ChallengeTemplate(code, type, "Template $code", "", "completed_workouts", "workouts", targets.map { TargetOption(it, null) }, 25)

fun testChallenge(
    id: String,
    templateCode: String = "WEEKLY_WORKOUTS",
    status: ChallengeStatus = ChallengeStatus.ACTIVE,
    progress: Int = 1,
    target: Int = 3,
    windowEnd: LocalDate? = LocalDate.of(2026, 10, 25),
    waterGoalMl: Int? = null,
    unit: String = "workouts",
) = Challenge(
    id, templateCode, ChallengeType.WEEKLY, "Challenge $id", target, progress, unit,
    LocalDate.of(2026, 10, 19), windowEnd, ZoneId.of("Asia/Kolkata"), waterGoalMl, status,
    Instant.parse("2026-10-20T04:00:00Z"), null, null, 0,
)

/** WEEKLY_WATER_DAYS joined with a 2500 ml goal (information only). */
fun testWaterChallenge(id: String = "w1", waterGoalMl: Int? = 2500, status: ChallengeStatus = ChallengeStatus.ACTIVE, progress: Int = 2, target: Int = 5) =
    testChallenge(id, "WEEKLY_WATER_DAYS", status, progress, target, waterGoalMl = waterGoalMl, unit = "days")

/** HYDRATED_7: 7 days at that day's own water goal (else 2000 ml). */
fun testHydratedBadge(earnedAt: Instant? = null, current: Int = 3) =
    testBadge("HYDRATED_7", earnedAt, BadgeProgress(current, 7), BadgeFamily.HYDRATION)

/**
 * In-memory [AchievementsRepository] following contract #43's rules: one active challenge per
 * template, at most 5 active, and a same-key join is replayed rather than duplicated.
 */
class FakeAchievementsRepository : AchievementsRepository {
    var badgeList = BadgeList(emptyList(), 0)
    val templateList = mutableListOf<ChallengeTemplate>()
    val challengeList = mutableListOf<Challenge>()
    /** When set, every call fails with it (e.g. [ApiError.Network]). */
    var failWith: ApiError? = null
    val joinKeys = mutableListOf<String>()
    val leaveKeys = mutableListOf<String>()
    private val replay = mutableMapOf<String, Challenge>()
    private var nextId = 0

    private fun <T> result(v: () -> T): ApiResult<T> = failWith?.let { ApiResult.Failure(it) } ?: ApiResult.Success(v())
    private fun http(status: Int, code: String) = ApiResult.Failure(ApiError.Http(status, code, code, null))

    override suspend fun badges() = result { badgeList }
    override suspend fun templates() = result { templateList.toList() }
    override suspend fun challenges(status: ChallengeStatus?, cursor: String?) =
        result { ChallengePage(challengeList.filter { status == null || it.status == status }, null) }

    override suspend fun join(templateCode: String, target: Int, idempotencyKey: String): ApiResult<Challenge> {
        joinKeys += idempotencyKey
        failWith?.let { return ApiResult.Failure(it) }
        replay[idempotencyKey]?.let { return ApiResult.Success(it) }
        val active = challengeList.filter { it.status == ChallengeStatus.ACTIVE }
        if (active.any { it.templateCode == templateCode }) return http(409, AchievementErrors.ALREADY_JOINED)
        if (active.size >= 5) return http(409, AchievementErrors.LIMIT_REACHED)
        val c = testChallenge("c${++nextId}", templateCode, progress = 0, target = target)
        challengeList.add(0, c)
        replay[idempotencyKey] = c
        return ApiResult.Success(c)
    }

    override suspend fun leave(id: String, idempotencyKey: String): ApiResult<Challenge> {
        leaveKeys += idempotencyKey
        failWith?.let { return ApiResult.Failure(it) }
        val i = challengeList.indexOfFirst { it.id == id }
        if (i < 0) return http(404, "NOT_FOUND")
        val c = challengeList[i]
        return when (c.status) {
            ChallengeStatus.ACTIVE, ChallengeStatus.LEFT -> {
                val left = c.copy(status = ChallengeStatus.LEFT)
                challengeList[i] = left
                ApiResult.Success(left)
            }
            else -> http(409, AchievementErrors.NOT_ACTIVE)
        }
    }
}

/** In-memory [com.vipin.fitness.core.data.achievements.SeenBadgeStore] with a settable signed-in user. */
class InMemorySeenBadgeStore(var owner: String? = "u1") : com.vipin.fitness.core.data.achievements.SeenBadgeStore {
    private val seen = mutableMapOf<String, MutableSet<String>>()
    override suspend fun currentOwner() = owner
    override suspend fun recordEarned(owner: String, codes: List<String>): List<String> {
        val first = owner !in seen
        val set = seen.getOrPut(owner) { mutableSetOf() }
        val fresh = codes.distinct().filter { set.add(it) }
        return if (first) emptyList() else fresh
    }
    override suspend fun clearAll() = seen.clear()
    override suspend fun keepOnly(owner: String) { seen.keys.retainAll(setOf(owner)) }
}
