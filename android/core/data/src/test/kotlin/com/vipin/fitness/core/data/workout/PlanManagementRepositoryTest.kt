package com.vipin.fitness.core.data.workout

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.PlanManagementApi
import com.vipin.fitness.core.network.WorkoutApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import com.vipin.fitness.core.network.model.WorkoutPlanDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import retrofit2.Response
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

    private fun planJson(week: Int, active: Boolean = true, pausedAt: String? = null, archivedAt: String? = null) =
        """{"id":"p1","version":1,"name":"Strong","weeks":8,"workouts":[],"isActive":$active,"source":"custom","createdAt":"t","currentWeek":$week,""" +
            """"pausedAt":${pausedAt?.let { "\"$it\"" } ?: "null"},"archivedAt":${archivedAt?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun restartOfTheActivePlanPausesThenActivatesWithNoBodyAndResetsTheWeek() = runTest {
        server.enqueue(MockResponse(code = 204))                    // deactivate
        server.enqueue(MockResponse(code = 200, body = planJson(1))) // activate, no body
        server.enqueue(MockResponse(code = 200, body = planJson(1))) // GET /active
        val plan = (repo.restart("p1", "k1", wasActive = true) as ApiResult.Success).value
        assertEquals(1, plan.currentWeek)
        val d = server.takeRequest()
        assertEquals("/api/v1/workout-plans/p1/deactivate", d.url.encodedPath)
        val dKey = d.headers["Idempotency-Key"]!!
        assertTrue(dKey != "k1" && java.util.UUID.fromString(dKey) != null)
        val a = server.takeRequest()
        assertEquals("/api/v1/workout-plans/p1/activate", a.url.encodedPath)
        assertEquals("k1", a.headers["Idempotency-Key"])
        assertEquals(0, a.body?.size ?: 0)
        assertEquals(1, (workouts.activePlan.value as ActivePlan.Some).plan.currentWeek)
        server.takeRequest() // GET /active
        // A retry of the same restart reuses both keys.
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 200, body = planJson(1)))
        server.enqueue(MockResponse(code = 200, body = planJson(1)))
        repo.restart("p1", "k1", wasActive = true)
        assertEquals(dKey, server.takeRequest().headers["Idempotency-Key"])
    }

    @Test fun unarchiveReturnsTheRestoredRow() = runTest {
        server.enqueue(MockResponse(code = 200, body = planJson(3, active = false, pausedAt = "2026-10-20T00:00:00Z")))
        val u = (repo.unarchive("p1", "k") as ApiResult.Success).value
        assertEquals(PlanStatus.PAUSED, u.status)
        assertEquals("2026-10-20T00:00:00Z", u.pausedAt)
        server.enqueue(MockResponse(code = 200, body = planJson(1, active = false)))
        assertEquals(PlanStatus.INACTIVE, (repo.unarchive("p1", "k2") as ApiResult.Success).value.status)
        assertEquals(2, server.requestCount) // no reload, no active refresh
    }

    /** Delegates to the server-backed api but holds GET /active until [gate] completes. */
    private class HeldActive(private val real: WorkoutApi, val gate: CompletableDeferred<Unit>) : WorkoutApi by real {
        val started = CompletableDeferred<Unit>()
        override suspend fun active(): Response<WorkoutPlanDto> {
            started.complete(Unit)
            gate.await()
            return real.active()
        }
    }

    @Test fun activeResponseHeldAcrossSignOutIsNotCached() = runTest {
        val r = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        val held = HeldActive(r.create(WorkoutApi::class.java), CompletableDeferred())
        val scopedWorkouts = DefaultWorkoutRepository(held, json)
        val plans = DefaultPlanManagementRepository(r.create(PlanManagementApi::class.java), scopedWorkouts, json)
        server.enqueue(MockResponse(code = 204))                // deactivate
        server.enqueue(MockResponse(code = 200, body = plan))   // the held GET /active
        val call = async { plans.pause("p1", "k") }
        held.started.await()
        scopedWorkouts.clearUserState()                          // sign-out / user switch
        held.gate.complete(Unit)
        assertTrue(call.await() is ApiResult.Success)
        assertNull(scopedWorkouts.activePlan.value)
        // Without a sign-out in between the same response is cached.
        server.enqueue(MockResponse(code = 200, body = plan))
        scopedWorkouts.refreshActivePlan()
        assertTrue(scopedWorkouts.activePlan.value is ActivePlan.Some)
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
