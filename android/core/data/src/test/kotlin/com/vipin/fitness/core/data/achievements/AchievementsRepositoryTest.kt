package com.vipin.fitness.core.data.achievements

import com.vipin.fitness.core.network.AchievementsApi
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
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

class AchievementsRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultAchievementsRepository

    private fun challenge(status: String = "active", type: String = "weekly", tz: String = "Asia/Kolkata") = """
        {"id":"c1","templateCode":"WEEKLY_WORKOUTS","type":"$type","title":"3 workouts this week","target":3,"progress":1,
         "unit":"workouts","windowStart":"2026-10-19","windowEnd":"2026-10-25","timezone":"$tz","waterGoalMl":null,
         "status":"$status","joinedAt":"2026-10-20T04:00:00Z","completedAt":null,"leftAt":null,"xpAwarded":0}
    """.trimIndent()

    @Before fun setUp() {
        server.start()
        repo = DefaultAchievementsRepository(retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(AchievementsApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    @Test fun badgesMapEarnedProgressAndUnknownFamily() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"items":[
                    {"code":"FIRST_WORKOUT","title":"First workout","description":"Complete 1 workout","family":"workout","bonusXp":10,"earnedAt":"2026-10-20T05:00:00Z","progress":null},
                    {"code":"MOON_WALK","title":"New","description":"From a newer server","family":"space","bonusXp":5,"earnedAt":null,"progress":{"current":2,"target":7},"extra":1}
                ],"earnedCount":1}""",
            ),
        )
        val list = (repo.badges() as ApiResult.Success).value
        assertEquals(1, list.earnedCount)
        assertEquals(BadgeFamily.WORKOUT, list.items[0].family)
        assertEquals(Instant.parse("2026-10-20T05:00:00Z"), list.items[0].earnedAt)
        assertEquals(BadgeFamily.UNKNOWN, list.items[1].family)
        assertEquals(BadgeProgress(2, 7), list.items[1].progress)
        assertEquals("/api/v1/badges", server.takeRequest().url.encodedPath)
    }

    @Test fun unknownTypeStatusAndTimezoneNeverCrash() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[${challenge(status = "paused", type = "team", tz = "Mars/Olympus")}],"nextCursor":null}"""))
        val c = (repo.challenges() as ApiResult.Success).value.items.single()
        assertEquals(ChallengeStatus.UNKNOWN, c.status)
        assertEquals(ChallengeType.UNKNOWN, c.type)
        assertEquals(ZoneId.of("Asia/Kolkata"), c.timezone)
    }

    @Test fun waterChallengeKeepsTheGoalAtJoinAsInformation() = runTest {
        val water = challenge().replace("\"WEEKLY_WORKOUTS\"", "\"WEEKLY_WATER_DAYS\"").replace("\"waterGoalMl\":null", "\"waterGoalMl\":2500")
            .replace("\"unit\":\"workouts\"", "\"unit\":\"days\"").replace("\"target\":3,\"progress\":1", "\"target\":5,\"progress\":2")
        server.enqueue(MockResponse(code = 200, body = """{"items":[$water],"nextCursor":null}"""))
        val c = (repo.challenges() as ApiResult.Success).value.items.single()
        assertEquals("WEEKLY_WATER_DAYS", c.templateCode)
        assertEquals(2500, c.waterGoalMl)
        assertTrue(c.isWater)
        // Progress is the server's count of days at each day's own goal; the goal at join doesn't change it.
        assertEquals(2, c.progress)
        assertEquals(5, c.target)
    }

    @Test fun workoutChallengeHasNoWaterGoal() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[${challenge()}],"nextCursor":null}"""))
        val c = (repo.challenges() as ApiResult.Success).value.items.single()
        assertNull(c.waterGoalMl)
        assertEquals(false, c.isWater)
    }

    @Test fun hydrated7BadgeLockedAndEarned() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"items":[
                    {"code":"HYDRATED_7","title":"Hydrated","description":"Reach that day's water goal on 7 days","family":"hydration","bonusXp":20,"earnedAt":null,"progress":{"current":3,"target":7}}
                ],"earnedCount":0}""",
            ),
        )
        val locked = (repo.badges() as ApiResult.Success).value.items.single()
        assertEquals("HYDRATED_7", locked.code)
        assertEquals(BadgeFamily.HYDRATION, locked.family)
        assertEquals(20, locked.bonusXp)
        assertEquals(BadgeProgress(3, 7), locked.progress)
        assertNull(locked.earnedAt)
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"items":[
                    {"code":"HYDRATED_7","title":"Hydrated","description":"","family":"hydration","bonusXp":20,"earnedAt":"2026-10-28T18:30:00Z","progress":null}
                ],"earnedCount":1}""",
            ),
        )
        val earned = (repo.badges() as ApiResult.Success).value
        assertEquals(1, earned.earnedCount)
        assertEquals(Instant.parse("2026-10-28T18:30:00Z"), earned.items.single().earnedAt)
        assertNull(earned.items.single().progress)
    }

    @Test fun templatesPairTargetsWithDeadlines() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"items":[{"code":"PERSONAL_WORKOUTS_N","type":"personal","title":"Workouts","description":"d","metric":"completed_workouts",
                    "unit":"workouts","targets":[10,20],"deadlineDays":[30,60],"bonusXp":50}]}""",
            ),
        )
        val t = (repo.templates() as ApiResult.Success).value.single()
        assertEquals(listOf(TargetOption(10, 30), TargetOption(20, 60)), t.options)
        assertEquals(ChallengeType.PERSONAL, t.type)
    }

    @Test fun listSendsStatusCursorAndLimit() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[],"nextCursor":"n2"}"""))
        assertEquals("n2", (repo.challenges(ChallengeStatus.COMPLETED, "c1") as ApiResult.Success).value.nextCursor)
        val url = server.takeRequest().url
        assertEquals("completed", url.queryParameter("status"))
        assertEquals("c1", url.queryParameter("cursor"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test fun joinSendsKeyAndBody() = runTest {
        server.enqueue(MockResponse(code = 201, body = challenge()))
        val c = (repo.join("WEEKLY_WORKOUTS", 3, "k1") as ApiResult.Success).value
        assertEquals(LocalDate.of(2026, 10, 25), c.windowEnd)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("k1", req.headers["Idempotency-Key"])
        assertEquals("""{"templateCode":"WEEKLY_WORKOUTS","target":3}""", req.body!!.utf8())
    }

    @Test fun joinConflictKeepsTheContractCode() = runTest {
        server.enqueue(MockResponse(code = 409, body = """{"code":"CHALLENGE_LIMIT_REACHED","message":"5 active"}"""))
        val e = (repo.join("WEEKLY_WORKOUTS", 3, "k1") as ApiResult.Failure).error as ApiError.Http
        assertEquals(AchievementErrors.LIMIT_REACHED, e.code)
    }

    @Test fun leavePostsToTheChallenge() = runTest {
        server.enqueue(MockResponse(code = 200, body = challenge(status = "left")))
        assertEquals(ChallengeStatus.LEFT, (repo.leave("c1", "k2") as ApiResult.Success).value.status)
        val req = server.takeRequest()
        assertEquals("/api/v1/challenges/c1/leave", req.url.encodedPath)
        assertEquals("k2", req.headers["Idempotency-Key"])
    }

    @Test fun daysLeftUsesTheChallengeTimezone() = runTest {
        server.enqueue(MockResponse(code = 200, body = challenge()))
        val c = (repo.leave("c1", "k") as ApiResult.Success).value
        // Sun 25 Oct 00:30 IST is still Sat 24 Oct in UTC: the challenge's zone decides.
        assertEquals(1, c.daysLeft(Instant.parse("2026-10-24T19:00:00Z")))
        assertEquals(7, c.daysLeft(Instant.parse("2026-10-19T00:00:00Z")))
        assertEquals(0, c.daysLeft(Instant.parse("2026-10-30T00:00:00Z")))
        assertNull(c.copy(windowEnd = null).daysLeft(Instant.EPOCH))
    }
}
