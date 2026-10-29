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
import com.vipin.fitness.core.network.di.buildRetrofit
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
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.vipin.fitness.core.data.sync.OutboxSyncWorker
import okhttp3.Headers.Companion.headersOf
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

private class WkClock(var ms: Long = Instant.parse("2026-10-21T04:30:00Z").toEpochMilli()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
    override fun instant(): Instant = Instant.ofEpochMilli(ms)
}

/**
 * QA (#133): a 429 must not use up one of OutboxSyncWorker's retry attempts (MAX_ATTEMPTS = 12). The worker
 * should end the run as success and schedule a new run after Retry-After, even on its last attempt.
 * Local MockWebServer only, never prod.
 */
@RunWith(AndroidJUnit4::class)
class OutboxSyncWorkerRateLimitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, FitnessDatabase::class.java).allowMainThreadQueries().build()
    private val json = fitnessJson()
    private val clock = WkClock()
    private val delays = mutableListOf<Long>()
    private val sync = object : SyncScheduler {
        override fun requestSync() { delays += 0L }
        override fun requestSync(delayMs: Long) { delays += delayMs }
    }
    private val repo = DefaultWorkoutSessionRepository(db, json, clock, sync)
    private val server = MockWebServer()
    private lateinit var outbox: OutboxProcessor
    private val plan = WorkoutPlan(
        "p1", 2, "Plan", 4, null,
        listOf(PlannedWorkout(DayOfWeek.WEDNESDAY, "Legs", listOf(PlannedExercise("squat", 1, 8, 10, targetWeightKg = 60.0, restSeconds = 120)), id = "w1")),
        true, PlanSource.CUSTOM,
    )
    private val squat = Exercise("squat", "Squat", null, null, emptyList(), emptyList(), TrackingType.REPS_WEIGHT, null, emptyList(), null)

    @Before fun setUp() {
        server.start()
        outbox = OutboxProcessor(db.pendingWriteDao(), db.workoutSessionDao(), buildRetrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(OutboxApi::class.java), json, clock)
    }
    @After fun tearDown() { db.close(); server.close() }

    private fun worker(runAttemptCount: Int) = TestListenableWorkerBuilder<OutboxSyncWorker>(context)
        .setRunAttemptCount(runAttemptCount)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters): ListenableWorker = OutboxSyncWorker(c, p, outbox, sync)
        })
        .build()
    private suspend fun queueOne() { repo.start(plan, plan.workouts[0], mapOf("squat" to squat)); delays.clear() }
    private fun limited(s: String) = MockResponse(code = 429, headers = headersOf("Retry-After", s), body = """{"code":"RATE_LIMITED","message":"x"}""")

    @Test fun a429OnTheLastAttemptIsSuccessAndReschedulesAfterRetryAfter() = runTest {
        queueOne()
        server.enqueue(limited("40"))
        assertEquals(ListenableWorker.Result.success(), worker(12).doWork())
        assertEquals(listOf(40_000L), delays)
        assertEquals(1, db.pendingWriteDao().oldest(10).size)
    }

    @Test fun a429OnTheFirstAttemptIsSuccessNotRetry() = runTest {
        queueOne()
        server.enqueue(limited("5"))
        assertEquals(ListenableWorker.Result.success(), worker(0).doWork())
        assertEquals(listOf(5_000L), delays)
    }

    @Test fun aRunInsideTheWindowSendsNothingAndReschedulesForTheRest() = runTest {
        queueOne()
        server.enqueue(limited("30"))
        worker(3).doWork()
        clock.ms += 10_000
        assertEquals(ListenableWorker.Result.success(), worker(4).doWork())
        assertEquals(listOf(30_000L, 20_000L), delays)
        assertEquals(1, server.requestCount)
    }

    @Test fun a503StillSpendsAttemptsAndFailsOnTheLast() = runTest {
        queueOne()
        server.enqueue(MockResponse(code = 503, body = """{"code":"UNAVAILABLE","message":"x"}"""))
        assertEquals(ListenableWorker.Result.retry(), worker(0).doWork())
        server.enqueue(MockResponse(code = 503, body = """{"code":"UNAVAILABLE","message":"x"}"""))
        assertEquals(ListenableWorker.Result.failure(), worker(12).doWork())
        assertTrue(delays.isEmpty())
    }

    @Test fun afterTheWindowTheQueueDrainsToSuccess() = runTest {
        queueOne()
        server.enqueue(limited("2"))
        worker(0).doWork()
        clock.ms += 2_001
        server.enqueue(MockResponse(code = 201, body = """{"id":"srv1","status":"in_progress","startedAt":"2026-10-21T04:30:00Z","activeSeconds":0,"sets":[]}"""))
        assertEquals(ListenableWorker.Result.success(), worker(1).doWork())
        assertTrue(db.pendingWriteDao().oldest(10).isEmpty())
        assertNull(outbox.rateLimitDelayMs())
    }
}
