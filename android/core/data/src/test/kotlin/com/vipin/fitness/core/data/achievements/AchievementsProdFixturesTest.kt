package com.vipin.fitness.core.data.achievements

import com.vipin.fitness.core.network.AchievementsApi
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.buildRetrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Real responses captured from prod (2026-10-21, after #49 deployed) with a throwaway account
 * that was then deleted. Ids are replaced and request ids removed; nothing else is edited.
 */
class AchievementsProdFixturesTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultAchievementsRepository

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResource("fixtures/achievements/$name.json")) { name }.readText()

    private fun serve(name: String, code: Int = 200) = server.enqueue(MockResponse(code = code, body = fixture(name)))

    @Before fun setUp() {
        server.start()
        repo = DefaultAchievementsRepository(buildRetrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(AchievementsApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    @Test fun badgesCatalog() = runTest {
        serve("badges")
        val list = (repo.badges() as ApiResult.Success).value
        assertEquals(16, list.items.size)
        assertEquals(0, list.earnedCount)
        assertTrue(list.items.none { it.family == BadgeFamily.UNKNOWN }) // every prod family is known
        val first = list.items.first()
        assertEquals("FIRST_WORKOUT", first.code)
        assertNull(first.earnedAt)
        assertEquals(BadgeProgress(0, 1), first.progress)
    }

    @Test fun templatesIncludingPersonalWithAndWithoutDeadlines() = runTest {
        serve("templates")
        val t = (repo.templates() as ApiResult.Success).value.associateBy { it.code }
        assertEquals(6, t.size)
        assertTrue(t.values.none { it.type == ChallengeType.UNKNOWN })
        assertEquals(listOf(2, 3, 4, 5), t.getValue("WEEKLY_WORKOUTS").options.map { it.target })
        assertEquals(listOf(TargetOption(10, 30), TargetOption(20, 60), TargetOption(30, 90)), t.getValue("PERSONAL_WORKOUTS_N").options)
        // Personal but open-ended: prod sends deadlineDays null.
        assertTrue(t.getValue("PERSONAL_STREAK_N").options.all { it.deadlineDays == null })
    }

    @Test fun emptyAndJoinedChallengeLists() = runTest {
        serve("challenges_empty")
        assertTrue((repo.challenges() as ApiResult.Success).value.items.isEmpty())
        serve("challenges")
        val c = (repo.challenges() as ApiResult.Success).value.items.single()
        assertEquals(ChallengeStatus.ACTIVE, c.status)
        assertEquals(LocalDate.of(2026, 10, 19), c.windowStart)
        assertEquals(LocalDate.of(2026, 10, 25), c.windowEnd)
        assertEquals(LocalDate.of(2026, 10, 21), c.joinDate)
        assertEquals(ZoneId.of("Asia/Kolkata"), c.timezone)
    }

    @Test fun joinAndLeave() = runTest {
        serve("join_201", 201)
        val joined = (repo.join("WEEKLY_WORKOUTS", 3, "k") as ApiResult.Success).value
        assertEquals(Instant.parse("2026-10-21T11:19:51.227Z"), joined.joinedAt)
        assertEquals(0, joined.xpAwarded)
        serve("leave_200")
        val left = (repo.leave(joined.id, "k2") as ApiResult.Success).value
        assertEquals(ChallengeStatus.LEFT, left.status)
        assertEquals(Instant.parse("2026-10-21T11:20:05.836Z"), left.leftAt)
    }

    @Test fun alreadyJoinedError() = runTest {
        serve("join_409_already_joined", 409)
        val e = (repo.join("WEEKLY_WORKOUTS", 3, "k") as ApiResult.Failure).error as ApiError.Http
        assertEquals(AchievementErrors.ALREADY_JOINED, e.code)
    }
}
