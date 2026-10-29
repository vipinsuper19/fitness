package com.vipin.fitness.core.data.workout

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.PlanManagementApi
import com.vipin.fitness.core.network.WorkoutApi
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

class PlanManagementRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var workouts: DefaultWorkoutRepository
    private lateinit var repo: DefaultPlanManagementRepository

    private val plan = """{"id":"p1","version":1,"name":"Strong","weeks":8,"workouts":[],"isActive":true,"source":"custom","createdAt":"t"}"""

    @Before fun setUp() {
        server.start()
        val r = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        workouts = DefaultWorkoutRepository(r.create(WorkoutApi::class.java), json)
        repo = DefaultPlanManagementRepository(r.create(PlanManagementApi::class.java), workouts, json)
    }

    @After fun tearDown() = server.close()

    @Test fun listSendsStatusAndCursorAndMapsUnknownStatus() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[
            {"id":"a","name":"A","source":"rule_based","version":2,"status":"paused","isActive":false,"currentWeek":3,"totalWeeks":8,"createdAt":"t","completedSessions":5},
            {"id":"b","name":"B","source":"custom","version":1,"status":"someday","totalWeeks":4,"createdAt":"t"}],"nextCursor":"n2"}"""))
        val page = (repo.list(PlanStatus.PAUSED, "c1") as ApiResult.Success).value
        val req = server.takeRequest()
        assertEquals("/api/v1/workout-plans?status=paused&cursor=c1&limit=20", req.url.encodedPath + "?" + req.url.encodedQuery)
        assertEquals(PlanStatus.PAUSED, page.items[0].status)
        assertEquals(3, page.items[0].currentWeek)
        assertEquals(PlanStatus.INACTIVE, page.items[1].status)
        assertEquals("n2", page.nextCursor)
        server.enqueue(MockResponse(code = 200, body = """{"items":[]}"""))
        repo.list(null)
        assertNull(server.takeRequest().url.queryParameter("status"))
    }

    @Test fun writesHitTheirEndpointsWithKeysAndSyncTheActivePlan() = runTest {
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204)) // GET /active after pause
        assertTrue(repo.pause("p1", "k1") is ApiResult.Success)
        server.takeRequest().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/workout-plans/p1/deactivate", it.url.encodedPath)
            assertEquals("k1", it.headers["Idempotency-Key"])
        }
        assertEquals("/api/v1/workout-plans/active", server.takeRequest().url.encodedPath)
        assertEquals(ActivePlan.None, workouts.activePlan.value)

        server.enqueue(MockResponse(code = 200, body = plan))
        server.enqueue(MockResponse(code = 200, body = plan))
        repo.resume("p1", "k2")
        assertEquals("""{"resume":true}""", server.takeRequest().body?.utf8())
        server.takeRequest()
        assertTrue(workouts.activePlan.value is ActivePlan.Some)

        server.enqueue(MockResponse(code = 200, body = plan))
        server.enqueue(MockResponse(code = 200, body = plan))
        repo.restart("p1", "k3")
        server.takeRequest().let { assertEquals("/api/v1/workout-plans/p1/activate", it.url.encodedPath); assertEquals(0, it.body?.size ?: 0) }
        server.takeRequest()

        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204))
        repo.archive("p1", "k4")
        assertEquals("DELETE", server.takeRequest().method)
        server.takeRequest()

        server.enqueue(MockResponse(code = 200, body = plan))
        repo.unarchive("p1", "k5")
        assertEquals("/api/v1/workout-plans/p1/unarchive", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse(code = 200, body = plan))
        server.enqueue(MockResponse(code = 200, body = plan))
        repo.rename("p1", "Strong", "k6")
        server.takeRequest().let {
            assertEquals("PUT", it.method); assertEquals("/api/v1/workout-plans/p1/name", it.url.encodedPath)
            assertEquals("""{"name":"Strong"}""", it.body?.utf8())
        }
    }

    @Test fun planArchivedIsRecognised() = runTest {
        server.enqueue(MockResponse(code = 409, body = """{"code":"PLAN_ARCHIVED","message":"x"}"""))
        val r = repo.rename("p1", "x", "k")
        assertTrue((r as ApiResult.Failure).error.isPlanArchived())
        assertEquals(1, server.requestCount) // no active refresh on failure
    }

    @Test fun nameNormalizes() {
        assertNull(PlanName.normalize("   "))
        assertEquals("ab", PlanName.normalize("  ab "))
        assertNull(PlanName.normalize("x".repeat(81)))
        assertEquals(80, PlanName.normalize("x".repeat(80))?.length)
    }
}
