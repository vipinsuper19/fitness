package com.vipin.fitness.core.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.sync.DefaultOfflineSync
import com.vipin.fitness.core.data.sync.DrainResult
import com.vipin.fitness.core.data.sync.OutboxProcessor
import com.vipin.fitness.core.data.sync.SyncScheduler
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlannedExercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.data.workout.WorkoutPlan
import com.vipin.fitness.core.data.workout.Exercise
import com.vipin.fitness.core.database.FitnessDatabase
import com.vipin.fitness.core.database.outbox.PendingWriteEntity
import com.vipin.fitness.core.network.OutboxApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.flow.first
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
import org.junit.runner.RunWith
import java.time.Clock
import java.time.DayOfWeek
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertNotEquals
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

private class RlClock(var ms: Long = Instant.parse("2026-10-21T04:30:00Z").toEpochMilli()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
    override fun instant(): Instant = Instant.ofEpochMilli(ms)
}

/**
 * QA (#133): a 429 parks the outbox without reordering, re-keying or spending attempts while it waits.
 * Fills gaps left by WorkoutTrackingTest's single-write 429 tests. Local MockWebServer only, never prod.
 */
@RunWith(AndroidJUnit4::class)
class OutboxRateLimitTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FitnessDatabase::class.java)
        .allowMainThreadQueries().build()
    private val json = fitnessJson()
    private val clock = RlClock()
    private val sync = object : SyncScheduler { override fun requestSync() {} }
    private val repo = DefaultWorkoutSessionRepository(db, json, clock, sync)
    private val server = MockWebServer()
    private lateinit var outbox: OutboxProcessor
    private val plan = WorkoutPlan(
        "p1", 2, "Plan", 4, null,
        listOf(PlannedWorkout(DayOfWeek.WEDNESDAY, "Legs", listOf(PlannedExercise("squat", 2, 8, 10, targetWeightKg = 60.0, restSeconds = 120)), id = "w1")),
        true, PlanSource.CUSTOM,
    )
    private val squat = Exercise("squat", "Squat", null, null, emptyList(), emptyList(), TrackingType.REPS_WEIGHT, null, emptyList(), null)
    private val sessionJson = """{"id":"srv1","status":"in_progress","startedAt":"2026-10-21T04:30:00Z","activeSeconds":0,"sets":[]}"""
    private val setJson = """{"clientSetId":"x","exerciseId":"squat","setIndex":0,"completed":true,"id":"l1","updatedAt":"t"}"""
    private fun limited(retryAfter: String?) = MockResponse(
        code = 429, headers = if (retryAfter == null) headersOf() else headersOf("Retry-After", retryAfter),
        body = """{"code":"RATE_LIMITED","message":"x"}""",
    )

    @Before fun setUp() {
        server.start()
        outbox = OutboxProcessor(db.pendingWriteDao(), db.workoutSessionDao(), retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(OutboxApi::class.java), json, clock)
    }

    @After fun tearDown() { db.close(); server.close() }

    private suspend fun queued() = db.pendingWriteDao().oldest(100)
    private suspend fun startWithTwoSets() {
        repo.start(plan, plan.workouts[0], mapOf("squat" to squat))
        val sets = repo.unfinished.first()!!.exercises[0].sets
        repo.setCompleted(sets[0].clientSetId, true)
        repo.setCompleted(sets[1].clientSetId, true)
    }

    @Test fun a429OnTheFirstWriteHoldsTheWholeQueueInOrderWithKeysAndAttempts() = runTest {
        startWithTwoSets()
        val before = queued()
        assertEquals(3, before.size)
        server.enqueue(limited("10"))
        assertEquals(DrainResult.Retry, outbox.drain())
        assertEquals(1, server.requestCount) // nothing behind the limited write was sent
        val after = queued()
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.idempotencyKey }, after.map { it.idempotencyKey })
        assertEquals(listOf(1, 0, 0), after.map { it.attempts })

        repeat(3) { clock.ms += 2_000; assertEquals(DrainResult.Retry, outbox.drain()) } // inside the window
        assertEquals(1, server.requestCount)
        assertEquals(listOf(1, 0, 0), queued().map { it.attempts }) // waiting spends no attempts

        clock.ms += 4_001
        server.enqueue(MockResponse(code = 201, body = sessionJson))
        server.enqueue(MockResponse(code = 200, body = setJson))
        server.enqueue(MockResponse(code = 200, body = setJson))
        assertEquals(DrainResult.Done, outbox.drain())
        val sent = (1..4).map { server.takeRequest() }.drop(1)
        assertEquals(listOf("/api/v1/workout-sessions", "/api/v1/workout-sessions/srv1/sets", "/api/v1/workout-sessions/srv1/sets"), sent.map { it.url.encodedPath })
        assertEquals(before.map { it.idempotencyKey }, sent.map { it.headers["Idempotency-Key"] })
        assertTrue(queued().isEmpty())
    }

    @Test fun a429MidQueueKeepsTheSentWriteGoneAndTheRestInPlace() = runTest {
        startWithTwoSets()
        val keys = queued().map { it.idempotencyKey }
        server.enqueue(MockResponse(code = 201, body = sessionJson))
        server.enqueue(limited("5"))
        assertEquals(DrainResult.Retry, outbox.drain())
        val q = queued()
        assertEquals(keys.drop(1), q.map { it.idempotencyKey })
        assertEquals(listOf(1, 0), q.map { it.attempts })
        assertEquals("RATE_LIMITED", q[0].lastError)
        assertEquals(5_000L, outbox.rateLimitDelayMs())
    }

    @Test fun aSecond429ExtendsTheWaitAndCountsOneMoreAttempt() = runTest {
        startWithTwoSets()
        server.enqueue(limited("3"))
        outbox.drain()
        clock.ms += 3_001
        server.enqueue(limited("7"))
        assertEquals(DrainResult.Retry, outbox.drain())
        assertEquals(7_000L, outbox.rateLimitDelayMs())
        assertEquals(listOf(2, 0, 0), queued().map { it.attempts })
        assertEquals(2, server.requestCount)
    }

    @Test fun missingRetryAfterWaitsTheDefault() = runTest {
        startWithTwoSets(); server.enqueue(limited(null)); outbox.drain()
        assertEquals(OutboxProcessor.DEFAULT_RATE_LIMIT_WAIT_MS, outbox.rateLimitDelayMs())
    }

    @Test fun hugeRetryAfterIsCappedAtAnHour() = runTest {
        startWithTwoSets(); server.enqueue(limited("999999")); outbox.drain()
        assertEquals(OutboxProcessor.MAX_RATE_LIMIT_WAIT_MS, outbox.rateLimitDelayMs())
    }

    @Test fun zeroRetryAfterStillWaitsTheMinimum() = runTest {
        startWithTwoSets(); server.enqueue(limited("0")); outbox.drain()
        assertEquals(OutboxProcessor.MIN_RATE_LIMIT_WAIT_MS, outbox.rateLimitDelayMs())
    }

    @Test fun a503AfterTheWindowIsNormalBackoffNotARateLimit() = runTest {
        startWithTwoSets(); server.enqueue(limited("2")); outbox.drain()
        clock.ms += 2_001
        server.enqueue(MockResponse(code = 503, body = """{"code":"UNAVAILABLE","message":"x"}"""))
        assertEquals(DrainResult.Retry, outbox.drain())
        assertNull(outbox.rateLimitDelayMs())
        assertEquals(listOf(2, 0, 0), queued().map { it.attempts })
        assertNotEquals("RATE_LIMITED", queued()[0].lastError)
    }
}
