package com.vipin.fitness.core.data.history

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.SessionCorrectionApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.buildRetrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class SessionCorrectionRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private val changes = SessionChanges()
    private lateinit var repo: DefaultSessionCorrectionRepository
    private val session = """{"id":"s1","status":"completed","startedAt":"2026-10-21T04:00:00Z","completedAt":"2026-10-21T05:00:00Z","activeSeconds":3000,
        |"sets":[{"id":"l1","clientSetId":"cs1","exerciseId":"e1","setIndex":0,"completed":true,"reps":12,"weightKg":60}]}""".trimMargin()
    private val xp = """{"total":140,"level":2,"xpIntoLevel":40,"xpForNextLevel":200}"""

    @Before fun setUp() {
        server.start()
        repo = DefaultSessionCorrectionRepository(buildRetrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(SessionCorrectionApi::class.java), changes, json)
    }

    @After fun tearDown() = server.close()

    @Test fun upsertPutsBySetIdWithAKeyAndMapsTheResult() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"session":$session,"xpDelta":-5,"xpBalance":$xp,"newBadges":[{"code":"b1","title":"Ten","family":"workouts","bonusXp":25,"earnedAt":"2026-10-21T05:01:00Z"}]}"""))
        val set = PastSet("cs1", "e1", 0, true, 12, 60.0, null, null)
        val r = (repo.upsertSet("s1", set) as ApiResult.Success).value
        val req = server.takeRequest()
        assertEquals("PUT" to "/api/v1/workout-sessions/s1/corrections/sets/cs1", req.method to req.url.encodedPath)
        UUID.fromString(req.headers["Idempotency-Key"])
        assertEquals("""{"clientSetId":"cs1","exerciseId":"e1","setIndex":0,"completed":true,"reps":12,"weightKg":60.0}""", req.body!!.utf8())
        assertEquals(-5, r.xpDelta)
        assertEquals(140, r.xpBalance.total)
        assertEquals(listOf("b1"), r.newBadges.map { it.code })
        assertEquals(12, r.session.sets.single().reps)
    }

    @Test fun deleteAndCompletionMissingBadgesAreTolerated() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"session":$session,"xpDelta":0,"xpBalance":$xp}"""))
        assertTrue((repo.deleteSet("s1", "cs2") as ApiResult.Success).value.newBadges.isEmpty())
        val del = server.takeRequest()
        assertEquals("DELETE" to "/api/v1/workout-sessions/s1/corrections/sets/cs2", del.method to del.url.encodedPath)
        server.enqueue(MockResponse(code = 200, body = """{"session":$session,"xpDelta":-10,"xpBalance":$xp}"""))
        repo.correctCompletion("s1", completedAt = Instant.parse("2026-10-21T04:50:00Z"), perceivedDifficulty = 4)
        val patch = server.takeRequest()
        assertEquals("PATCH" to "/api/v1/workout-sessions/s1/completion", patch.method to patch.url.encodedPath)
        assertEquals("""{"completedAt":"2026-10-21T04:50:00Z","perceivedDifficulty":4}""", patch.body!!.utf8())
    }

    @Test fun windowClosedComesBackAsItsCode() = runTest {
        server.enqueue(MockResponse(code = 409, body = """{"code":"CORRECTION_WINDOW_CLOSED","message":"x"}"""))
        val e = (repo.deleteSet("s1", "cs1") as ApiResult.Failure).error as ApiError.Http
        assertEquals(CorrectionErrors.WINDOW_CLOSED, e.code)
    }

    @Test fun discardIsADeleteAnd404CountsAsGone() = runTest {
        server.enqueue(MockResponse(code = 204))
        assertTrue(repo.discard("s1") is ApiResult.Success)
        assertEquals("DELETE" to "/api/v1/workout-sessions/s1", server.takeRequest().let { it.method to it.url.encodedPath })
        server.enqueue(MockResponse(code = 404, body = """{"code":"NOT_FOUND","message":"x"}"""))
        assertTrue(repo.discard("s1") is ApiResult.Success)
        server.enqueue(MockResponse(code = 500, body = """{"code":"INTERNAL","message":"x"}"""))
        assertTrue(repo.discard("s1") is ApiResult.Failure)
    }

    @Test fun windowIsTodayOrYesterdayInTheGivenZone() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.of(2026, 10, 29)
        assertTrue(CorrectionWindow.isOpen(Instant.parse("2026-10-29T01:00:00Z"), kolkata, today))
        assertTrue(CorrectionWindow.isOpen(Instant.parse("2026-10-27T19:00:00Z"), kolkata, today)) // 28th 00:30 IST
        assertFalse(CorrectionWindow.isOpen(Instant.parse("2026-10-27T18:00:00Z"), kolkata, today)) // 27th 23:30 IST
        // Same instant, New York: still the 27th there, so closed.
        assertFalse(CorrectionWindow.isOpen(Instant.parse("2026-10-28T03:00:00Z"), ZoneId.of("America/New_York"), today))
        assertFalse(CorrectionWindow.isOpen(null, kolkata, today))
    }

    @Test fun completedAtOnlyMovesEarlierOnTheSameLocalDate() {
        val z = ZoneId.of("Asia/Kolkata")
        val start = Instant.parse("2026-10-28T18:00:00Z") // 23:30 IST on the 28th
        val done = Instant.parse("2026-10-28T18:20:00Z")
        assertTrue(CorrectionWindow.validCompletedAt(Instant.parse("2026-10-28T18:10:00Z"), done, start, z))
        assertFalse(CorrectionWindow.validCompletedAt(Instant.parse("2026-10-28T18:25:00Z"), done, start, z)) // later
        assertFalse(CorrectionWindow.validCompletedAt(start, done, start, z)) // not after start
        val lateDone = Instant.parse("2026-10-28T18:40:00Z") // 00:10 IST on the 29th
        assertFalse(CorrectionWindow.validCompletedAt(Instant.parse("2026-10-28T18:25:00Z"), lateDone, start, z)) // back to the 28th
    }
}
