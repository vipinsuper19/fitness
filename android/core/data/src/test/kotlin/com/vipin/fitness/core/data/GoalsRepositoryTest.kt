package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.GoalsApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.buildRetrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class GoalsRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultGoalsRepository

    @Before fun setUp() {
        server.start()
        repo = DefaultGoalsRepository(buildRetrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(GoalsApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    private val goalJson = """{"id":"g1","type":"lose_weight","status":"active","targetValue":70,"startValue":80.5,"targetDate":"2027-01-31","createdAt":"2026-10-29T05:00:00.000Z","updatedAt":"2026-10-29T05:00:00.000Z"}"""

    @Test fun listParsesAndToleratesUnknownTypesAndStatuses() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[$goalJson,{"id":"g2","type":"run_marathon","status":"paused","createdAt":"x","updatedAt":"x"}]}"""))
        val items = (repo.list(GoalStatus.ACTIVE) as ApiResult.Success).value
        assertEquals("active", server.takeRequest().url.queryParameter("status"))
        assertEquals(Goal("g1", GoalType.LOSE_WEIGHT, GoalStatus.ACTIVE, 70.0, 80.5, LocalDate.of(2027, 1, 31)), items[0])
        assertEquals(GoalType.UNKNOWN, items[1].type)
        assertEquals(GoalStatus.UNKNOWN, items[1].status)
    }

    @Test fun createSendsOnlySetFieldsWithKey() = runTest {
        server.enqueue(MockResponse(code = 201, body = goalJson))
        repo.create(NewGoal(GoalType.WORKOUTS_PER_WEEK, 4.0, null, null), "k1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("k1", req.headers["Idempotency-Key"])
        assertEquals("""{"type":"workouts_per_week","targetValue":4.0}""", req.body!!.utf8())
    }

    @Test fun patchSendsStatusAndClearsWithNull() = runTest {
        server.enqueue(MockResponse(code = 200, body = goalJson))
        repo.update("g1", GoalChanges(status = GoalStatus.ACHIEVED, targetDate = Field.Clear), "k2")
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/v1/goals/g1", req.url.encodedPath)
        assertEquals("""{"status":"achieved","targetDate":null}""", req.body!!.utf8())
    }

    @Test fun deleteIs204AndA404IsAFailure() = runTest {
        server.enqueue(MockResponse(code = 204))
        assertEquals(ApiResult.Success(Unit), repo.delete("g1", "k3"))
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(MockResponse(code = 404, body = """{"code":"NOT_FOUND","message":"Goal not found"}"""))
        assert(repo.delete("g1", "k4") is ApiResult.Failure)
    }

    @Test fun progressMath() {
        fun g(type: GoalType, target: Double?, start: Double? = null) = Goal("g", type, GoalStatus.ACTIVE, target, start, null)
        val lose = goalProgress(g(GoalType.LOSE_WEIGHT, 70.0, 80.0), latestWeightKg = 77.0, workoutsThisWeek = 0)
        assertEquals(0.3, lose.fraction!!, 1e-9)
        assertEquals(25, lose.milestone)
        assertEquals(1.0, goalProgress(g(GoalType.LOSE_WEIGHT, 70.0, 80.0), 69.0, 0).fraction!!, 1e-9)
        assertEquals(0.0, goalProgress(g(GoalType.LOSE_WEIGHT, 70.0, 80.0), 82.0, 0).fraction!!, 1e-9)
        assertEquals(0.5, goalProgress(g(GoalType.GAIN_MUSCLE, 70.0, 60.0), 65.0, 0).fraction!!, 1e-9)
        assertNull(goalProgress(g(GoalType.LOSE_WEIGHT, 70.0, null), 75.0, 0).fraction)
        assertNull(goalProgress(g(GoalType.LOSE_WEIGHT, 70.0, 80.0), null, 0).fraction)
        assertEquals(true, goalProgress(g(GoalType.MAINTAIN, 70.0), 70.8, 0).onTrack)
        assertEquals(false, goalProgress(g(GoalType.MAINTAIN, 70.0), 71.5, 0).onTrack)
        val w = goalProgress(g(GoalType.WORKOUTS_PER_WEEK, 3.0), null, 3)
        assertEquals(100, w.milestone)
        assertNull(goalProgress(g(GoalType.UNKNOWN, 3.0), 70.0, 3).fraction)
    }
}
