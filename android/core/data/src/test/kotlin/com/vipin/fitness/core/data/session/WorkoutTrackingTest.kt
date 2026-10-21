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
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

private class StepClock(var ms: Long = Instant.parse("2026-10-21T04:30:00Z").toEpochMilli()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
    override fun instant(): Instant = Instant.ofEpochMilli(ms)
}

@RunWith(AndroidJUnit4::class)
class WorkoutTrackingTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FitnessDatabase::class.java)
        .allowMainThreadQueries().build()
    private val json = fitnessJson()
    private val clock = StepClock()
    private val sync = object : SyncScheduler {
        var requests = 0
        override fun requestSync() { requests++ }
    }
    private val repo = DefaultWorkoutSessionRepository(db, json, clock, sync)
    private val server = MockWebServer()
    private lateinit var outbox: OutboxProcessor

    private val plan = WorkoutPlan(
        "p1", 2, "Plan", 4, null,
        listOf(
            PlannedWorkout(
                DayOfWeek.WEDNESDAY, "Legs",
                listOf(PlannedExercise("squat", 2, 8, 10, targetWeightKg = 60.0, restSeconds = 120), PlannedExercise("plank", 1, durationSeconds = 45)),
                id = "w1",
            ),
        ),
        true, PlanSource.CUSTOM,
    )
    private val squat = Exercise("squat", "Squat", null, null, emptyList(), emptyList(), TrackingType.REPS_WEIGHT, null, emptyList(), null)
    private val lunge = squat.copy(id = "lunge", name = "Lunge")

    @Before fun setUp() {
        server.start()
        outbox = OutboxProcessor(db.pendingWriteDao(), db.workoutSessionDao(), retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(OutboxApi::class.java), json)
    }

    @After fun tearDown() {
        db.close()
        server.close()
    }

    private suspend fun start() = repo.start(plan, plan.workouts[0], mapOf("squat" to squat)).localId
    private suspend fun queued() = db.pendingWriteDao().oldest(100)
    private suspend fun session() = repo.unfinished.first()!!

    @Test fun startCreatesSetsFromThePlanAndQueuesTheStart() = runTest {
        val id = start()
        val s = session()
        assertEquals(id, s.localId)
        assertEquals(listOf(2, 1), s.exercises.map { it.sets.size })
        assertEquals(10, s.exercises[0].sets[0].reps)
        assertEquals(60.0, s.exercises[0].sets[0].weightKg!!, 0.0)
        // Unknown to the catalog cache: tracking falls back to the plan's duration target.
        assertEquals(TrackingType.DURATION, s.exercises[1].trackingType)
        assertEquals(45, s.exercises[1].sets[0].durationSeconds)
        val w = queued().single()
        assertEquals("POST" to "workout-sessions", w.method to w.path)
        assertEquals("""{"plannedWorkoutId":"w1","planVersion":2,"startedAt":"2026-10-21T04:30:00Z"}""", w.body)
        assertEquals(1, sync.requests)
    }

    @Test fun oneSessionAtATime() = runTest {
        val id = start()
        assertEquals(StartOutcome.AlreadyActive(id), repo.start(plan, plan.workouts[0], emptyMap()))
        assertEquals(1, queued().size)
    }

    @Test fun setToggleQueuesUpsertAgainstTheLocalSession() = runTest {
        val id = start()
        val set = session().exercises[0].sets[0]
        repo.setCompleted(set.clientSetId, true)
        repo.setCompleted(set.clientSetId, true) // no change: nothing queued
        val w = queued().last()
        assertEquals("workout-sessions/{session:$id}/sets", w.path)
        assertTrue(w.body!!.contains("\"clientSetId\":\"${set.clientSetId}\""))
        assertTrue(w.body!!.contains("\"completed\":true"))
        assertTrue(w.body!!.contains("\"reps\":10"))
        assertEquals(2, queued().size)
    }

    @Test fun editsToADoneSetAreSentOnPause() = runTest {
        start()
        val set = session().exercises[0].sets[0]
        repo.editSet(set.clientSetId, 12, 62.5, null) // not done yet: local only
        assertEquals(1, queued().size)
        repo.setCompleted(set.clientSetId, true)
        repo.editSet(set.clientSetId, 11, 62.5, null)
        assertEquals(2, queued().size)
        repo.pause()
        val paths = queued().map { it.path.substringAfterLast('/') }
        assertEquals(listOf("workout-sessions", "sets", "sets", "{session:${session().localId}}"), paths)
        assertTrue(queued()[2].body!!.contains("\"reps\":11"))
    }

    @Test fun pauseAndResumeCountOnlyActiveTime() = runTest {
        start()
        clock.ms += 60_000
        repo.pause()
        clock.ms += 600_000
        assertEquals(60, session().activeSeconds(clock.instant()))
        repo.resume()
        clock.ms += 30_000
        assertEquals(90, session().activeSeconds(clock.instant()))
        assertEquals(SessionStatus.IN_PROGRESS, session().status)
        assertEquals("""{"status":"in_progress"}""", queued().last().body)
    }

    @Test fun replaceKeepsDoneSetsOnTheOriginalExercise() = runTest {
        start()
        val sets = session().exercises[0].sets
        repo.setCompleted(sets[0].clientSetId, true)
        repo.replaceExercise(0, lunge)
        val e = session().exercises[0]
        assertEquals("lunge", e.exerciseId)
        assertTrue(e.replaced)
        assertEquals(listOf("squat", "lunge"), e.sets.map { it.exerciseId })
    }

    @Test fun finishNeedsADoneSetThenQueuesCompletion() = runTest {
        val id = start()
        assertEquals(FinishOutcome.NoCompletedSets, repo.finish(3, null))
        repo.setCompleted(session().exercises[0].sets[0].clientSetId, true)
        clock.ms += 1_800_000
        assertEquals(FinishOutcome.Finished, repo.finish(9, "  felt good "))
        assertNull(repo.unfinished.first())
        val done = repo.session(id).first()!!
        assertEquals(SessionStatus.COMPLETED, done.status)
        assertEquals(1800, done.activeSeconds(clock.instant().plusSeconds(999)))
        val w = queued().last()
        assertEquals("workout-sessions/{session:$id}/complete", w.path)
        assertEquals("""{"completedAt":"2026-10-21T05:00:00Z","perceivedDifficulty":5,"notes":"felt good"}""", w.body)
        assertEquals(FinishOutcome.NoSession, repo.finish(null, null))
    }

    @Test fun doneSessionIsReadOnly() = runTest {
        start()
        val set = session().exercises[0].sets[0]
        repo.setCompleted(set.clientSetId, true)
        repo.finish(null, null)
        val before = queued().size
        repo.setCompleted(set.clientSetId, false)
        assertEquals(before, queued().size)
    }

    @Test fun signOutClearsSessionsAndQueue() = runTest {
        start()
        repo.clearUserState()
        assertNull(repo.unfinished.first())
        assertTrue(queued().isEmpty())
    }

    // Sync

    private fun ok(body: String) = MockResponse(code = 200, body = body)
    private val sessionJson = """{"id":"srv1","status":"in_progress","startedAt":"2026-10-21T04:30:00Z","activeSeconds":0,"sets":[]}"""

    @Test fun syncSendsInOrderAndResolvesTheSessionId() = runTest {
        val id = start()
        repo.setCompleted(session().exercises[0].sets[0].clientSetId, true)
        repo.finish(4, null)
        val keys = queued().map { it.idempotencyKey }
        server.enqueue(MockResponse(code = 201, body = sessionJson))
        server.enqueue(ok("""{"clientSetId":"x","exerciseId":"squat","setIndex":0,"completed":true,"id":"l1","updatedAt":"t"}"""))
        server.enqueue(ok("""{"session":$sessionJson,"xpAwarded":40,"xpBalance":{"balance":40}}"""))
        assertEquals(DrainResult.Done, outbox.drain())
        val r1 = server.takeRequest()
        val r2 = server.takeRequest()
        val r3 = server.takeRequest()
        assertEquals("/api/v1/workout-sessions", r1.url.encodedPath)
        assertEquals("/api/v1/workout-sessions/srv1/sets", r2.url.encodedPath)
        assertEquals("/api/v1/workout-sessions/srv1/complete", r3.url.encodedPath)
        assertEquals(keys, listOf(r1, r2, r3).map { it.headers["Idempotency-Key"] })
        assertTrue(queued().isEmpty())
        val done = repo.session(id).first()!!
        assertTrue(done.synced)
        assertEquals(40, done.xpAwarded)
    }

    @Test fun serverDownKeepsEverythingQueuedInOrder() = runTest {
        start()
        repo.setCompleted(session().exercises[0].sets[0].clientSetId, true)
        server.enqueue(MockResponse(code = 503, body = """{"code":"UNAVAILABLE","message":"x"}"""))
        assertEquals(DrainResult.Retry, outbox.drain())
        assertEquals(1, server.requestCount)
        val q = queued()
        assertEquals(2, q.size)
        assertEquals(1, q[0].attempts)
        assertEquals("UNAVAILABLE", q[0].lastError)
    }

    @Test fun offlineIsRetry() = runTest {
        start()
        server.close()
        assertEquals(DrainResult.Retry, outbox.drain())
        assertEquals("NETWORK", queued().single().lastError)
    }

    @Test fun rejectedStartDropsItsWritesAndFlagsTheSession() = runTest {
        val id = start()
        repo.setCompleted(session().exercises[0].sets[0].clientSetId, true)
        server.enqueue(MockResponse(code = 400, body = """{"code":"VALIDATION_FAILED","message":"startedAt"}"""))
        assertEquals(DrainResult.Done, outbox.drain())
        assertEquals(1, server.requestCount) // the set never went to a session that doesn't exist
        assertTrue(queued().isEmpty())
        assertEquals("VALIDATION_FAILED", repo.session(id).first()!!.syncError)
    }

    @Test fun sessionCompletedElsewhereIsDone() = runTest {
        start()
        server.enqueue(MockResponse(code = 201, body = sessionJson))
        outbox.drain()
        repo.pause()
        server.enqueue(MockResponse(code = 409, body = """{"code":"SESSION_COMPLETED","message":"x"}"""))
        assertEquals(DrainResult.Done, outbox.drain())
        assertTrue(queued().isEmpty())
        assertNull(repo.session(session().localId).first()!!.syncError)
        server.takeRequest(1, TimeUnit.SECONDS)
    }

    // (a) Ownership

    @Test fun writesAndSessionsCarryTheSignedInUser() = runTest {
        repo.onSignedIn("u1")
        start()
        assertEquals(listOf("u1"), queued().map { it.ownerUserId })
    }

    @Test fun sameUserSigningBackInKeepsAndSyncsTheQueue() = runTest {
        repo.onSignedIn("u1")
        start()
        val before = sync.requests
        repo.onSignedIn("u1")
        assertEquals(1, queued().size)
        assertTrue(repo.unfinished.first() != null)
        assertEquals(before + 1, sync.requests)
    }

    @Test fun anotherUserSigningInDeletesWithoutSending() = runTest {
        repo.onSignedIn("u1")
        start()
        repo.onSignedIn("u2")
        assertTrue(queued().isEmpty())
        assertNull(repo.unfinished.first())
        assertEquals(0, server.requestCount)
    }

    @Test fun aWriteOfAnotherUserIsNeverSent() = runTest {
        repo.onSignedIn("u2")
        db.pendingWriteDao().enqueue(
            PendingWriteEntity("w", "k", "POST", "food-logs", "{}", 1, ownerUserId = "u1"),
        )
        assertEquals(DrainResult.Done, outbox.drain())
        assertEquals(0, server.requestCount)
        assertTrue(queued().isEmpty())
    }

    @Test fun confirmedSignOutClearsTheAccountToo() = runTest {
        repo.onSignedIn("u1")
        start()
        repo.clearUserState()
        assertNull(db.workoutSessionDao().currentUserId())
        assertTrue(queued().isEmpty())
    }

    @Test fun offlineSyncReportsUnsyncedWorkouts() = runTest {
        start()
        repo.setCompleted(session().exercises[0].sets[0].clientSetId, true)
        val offline = DefaultOfflineSync(outbox)
        server.enqueue(MockResponse(code = 503, body = ""))
        assertEquals(1, offline.syncNow()) // two writes, one workout
        server.enqueue(MockResponse(code = 201, body = sessionJson))
        server.enqueue(ok("""{"clientSetId":"x","exerciseId":"squat","setIndex":0,"completed":true,"id":"l1","updatedAt":"t"}"""))
        assertEquals(0, offline.syncNow())
    }

    // (c) Errors

    @Test fun idempotencyInProgressRetriesAndKeepsTheWrite() = runTest {
        start()
        server.enqueue(MockResponse(code = 409, body = """{"code":"IDEMPOTENCY_IN_PROGRESS","message":"x"}"""))
        assertEquals(DrainResult.Retry, outbox.drain())
        assertEquals("IDEMPOTENCY_IN_PROGRESS", queued().single().lastError)
        assertNull(session().syncError)
    }

    @Test fun sessionGoneDropsTheWriteAndFlagsIt() = runTest {
        val id = start()
        server.enqueue(MockResponse(code = 201, body = sessionJson))
        outbox.drain()
        repo.setCompleted(session().exercises[0].sets[0].clientSetId, true)
        server.enqueue(MockResponse(code = 404, body = """{"code":"NOT_FOUND","message":"x"}"""))
        assertEquals(DrainResult.Done, outbox.drain())
        assertTrue(queued().isEmpty())
        assertEquals(listOf(id), repo.flagged.first().map { it.localId })
        assertEquals("NOT_FOUND", repo.flagged.first().single().syncError)
        repo.dismissFlag(id)
        assertTrue(repo.flagged.first().isEmpty())
    }
}
