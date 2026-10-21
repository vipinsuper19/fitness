package com.vipin.fitness.core.data

import com.vipin.fitness.core.data.history.DefaultWorkoutHistoryRepository
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.WorkoutHistoryApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class WorkoutHistoryRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultWorkoutHistoryRepository

    // Includes fields the client doesn't model yet (e.g. from #57 corrections): must be ignored.
    private val session = """{"id":"s1","planId":null,"planVersion":null,"plannedWorkoutId":null,"status":"completed",
        "startedAt":"2026-10-20T06:00:00Z","completedAt":"2026-10-20T06:40:00Z","activeSeconds":2100,
        "perceivedDifficulty":3,"notes":"good","correctedAt":"2026-10-20T08:00:00Z",
        "sets":[
          {"id":"x2","clientSetId":"c2","exerciseId":"e1","setIndex":1,"reps":8,"weightKg":22.5,"durationSeconds":null,"distanceM":null,"completed":true,"loggedAt":"2026-10-20T06:10:00Z","updatedAt":"2026-10-20T06:10:00Z"},
          {"id":"x1","clientSetId":"c1","exerciseId":"e1","setIndex":0,"reps":10,"weightKg":20,"completed":true,"loggedAt":"2026-10-20T06:05:00Z","updatedAt":"2026-10-20T06:05:00Z"},
          {"id":"x3","clientSetId":"c3","exerciseId":"e2","setIndex":0,"durationSeconds":60,"completed":false,"loggedAt":"2026-10-20T06:20:00Z","updatedAt":"2026-10-20T06:20:00Z"}
        ]}"""

    @Before fun setUp() {
        server.start()
        repo = DefaultWorkoutHistoryRepository(retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(WorkoutHistoryApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    @Test fun historyPagesWithCursorAndLimit() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[$session],"nextCursor":"n2"}"""))
        val page = (repo.history("n1") as ApiResult.Success).value
        assertEquals("n2", page.nextCursor)
        val s = page.items.single()
        assertEquals(Instant.parse("2026-10-20T06:40:00Z"), s.completedAt)
        assertEquals(2, s.completedSets)
        assertEquals(listOf("c1", "c2", "c3"), s.sets.map { it.clientSetId }) // grouped by exercise, in set order
        val url = server.takeRequest().url
        assertEquals("/api/v1/workout-sessions/history", url.encodedPath)
        assertEquals("n1", url.queryParameter("cursor"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test fun firstPageSendsNoCursorAndLastPageHasNone() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[],"nextCursor":null}"""))
        val page = (repo.history() as ApiResult.Success).value
        assertNull(page.nextCursor)
        assertNull(server.takeRequest().url.queryParameter("cursor"))
    }

    @Test fun sessionDetailAndNotFound() = runTest {
        server.enqueue(MockResponse(code = 200, body = session))
        val s = (repo.session("s1") as ApiResult.Success).value
        assertEquals(60, s.sets.last().durationSeconds)
        assertEquals("/api/v1/workout-sessions/s1", server.takeRequest().url.encodedPath)
        server.enqueue(MockResponse(code = 404, body = """{"error":{"code":"NOT_FOUND","message":"Session not found","requestId":"r"}}"""))
        val err = (repo.session("gone") as ApiResult.Failure).error
        assertEquals(404, (err as ApiError.Http).status)
    }
}
