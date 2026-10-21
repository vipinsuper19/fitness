package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.data.session.SessionStatus
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlannedExercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.FakeWorkoutSessionRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.asPlan
import com.vipin.fitness.core.testing.testExercise
import com.vipin.fitness.core.testing.testSession
import com.vipin.fitness.core.testing.testSessionExercise
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class TestClock(var now: Instant = Instant.parse("2026-10-21T04:30:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    override fun instant(): Instant = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

class ActiveSessionViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val clock = TestClock()
    private val sessions = FakeWorkoutSessionRepository { clock.now }

    private fun startSession(rest: Int = 90) = testSession(
        "s1", "Legs",
        listOf(testSessionExercise(0, "squat", restSeconds = rest, sessionId = "s1"), testSessionExercise(1, "plank", tracking = TrackingType.DURATION, sessionId = "s1")),
        clock.now,
    ).also(sessions::put)

    private fun vm() = ActiveSessionViewModel(sessions, clock)
    private fun firstSet(vm: ActiveSessionViewModel) = vm.state.value.session!!.exercises[0].sets[0]

    @Test fun showsUnfinishedSessionAndActiveTime() {
        startSession()
        val vm = vm()
        clock.advance(65)
        vm.tick()
        assertEquals("s1", vm.state.value.session?.localId)
        assertEquals(65, vm.state.value.activeSeconds)
        assertEquals("1:05", formatClock(65))
    }

    @Test fun checkingASetStartsRestThatCountsDown() {
        startSession(rest = 60)
        val vm = vm()
        vm.toggleSet(firstSet(vm))
        assertTrue(firstSet(vm).completed)
        assertEquals(60, vm.state.value.restRemaining)
        clock.advance(45); vm.tick()
        assertEquals(15, vm.state.value.restRemaining)
        vm.addRest(30)
        assertEquals(45, vm.state.value.restRemaining)
        clock.advance(45); vm.tick()
        assertNull(vm.state.value.rest)
    }

    @Test fun uncheckingDoesNotStartRestAndSkipRestClears() {
        startSession()
        val vm = vm()
        vm.toggleSet(firstSet(vm))
        vm.skipRest()
        assertNull(vm.state.value.rest)
        vm.toggleSet(firstSet(vm))
        assertFalse(firstSet(vm).completed)
        assertNull(vm.state.value.rest)
    }

    @Test fun noRestWhenExerciseHasNone() {
        startSession(rest = 0)
        val vm = vm()
        vm.toggleSet(firstSet(vm))
        assertNull(vm.state.value.rest)
    }

    @Test fun pauseStopsTheClockAndClearsRest() {
        startSession()
        val vm = vm()
        vm.toggleSet(firstSet(vm))
        clock.advance(30)
        vm.togglePause()
        assertTrue(vm.state.value.paused)
        assertNull(vm.state.value.rest)
        clock.advance(600); vm.tick()
        assertEquals(30, vm.state.value.activeSeconds)
        vm.togglePause()
        clock.advance(10); vm.tick()
        assertEquals(40, vm.state.value.activeSeconds)
    }

    @Test fun editAndSkip() {
        startSession()
        val vm = vm()
        vm.editSet(firstSet(vm), 10, 40.0, null)
        assertEquals(10, firstSet(vm).reps)
        assertEquals(40.0, firstSet(vm).weightKg!!, 0.0)
        vm.setSkipped(1, true)
        assertTrue(vm.state.value.session!!.exercises[1].skipped)
    }

    @Test fun finishNeedsACompletedSet() {
        startSession()
        val vm = vm()
        vm.openFinish()
        vm.finish()
        assertEquals(FinishError.NO_COMPLETED_SETS, vm.state.value.finishError)
        assertNull(vm.state.value.finishedId)
    }

    @Test fun finishSendsDifficultyAndNotesOnce() {
        startSession()
        val vm = vm()
        vm.toggleSet(firstSet(vm))
        vm.openFinish()
        vm.setDifficulty(4)
        vm.setDifficulty(3)
        vm.setDifficulty(3) // tap again clears
        vm.setDifficulty(5)
        vm.setNotes("good")
        vm.finish()
        vm.finish()
        assertEquals(listOf(5 to "good"), sessions.finishes)
        assertEquals("s1", vm.state.value.finishedId)
        assertFalse(vm.state.value.showFinish)
        assertNotNull(vm.state.value.session) // still shown while leaving
    }

    @Test fun noSession() {
        val vm = vm()
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.session)
    }
}

class StartWorkoutTest {
    @get:Rule val main = MainDispatcherRule()
    private val clock = TestClock()
    private val repo = FakeWorkoutRepository().apply { catalog["squat"] = testExercise("squat", "Squat") }
    private val sessions = FakeWorkoutSessionRepository { clock.now }
    private val plan = PlanDraft(
        "P", 4, PlanSource.CUSTOM, null,
        listOf(PlannedWorkout(DayOfWeek.WEDNESDAY, "Legs", listOf(PlannedExercise("squat", 3, 8, 12), PlannedExercise("gone", 2)), id = "w1")),
    ).asPlan("p1", true)

    @Test fun startsTodaysWorkoutWithCatalogDetails() {
        repo.refreshResult = { ApiResult.Success(ActivePlan.Some(plan)) }
        val vm = WorkoutHomeViewModel(repo, clock, sessions)
        vm.startWorkout()
        vm.startWorkout()
        assertEquals(1, sessions.starts.size)
        assertEquals(setOf("squat"), sessions.starts.single().second.keys) // missing exercise falls back
        assertTrue(vm.state.value.openSession)
        assertEquals("Legs", vm.state.value.unfinishedSession?.name)
        vm.onSessionOpened()
        assertFalse(vm.state.value.openSession)
    }

    @Test fun restDayHasNothingToStart() {
        repo.refreshResult = { ApiResult.Success(ActivePlan.Some(plan)) }
        val vm = WorkoutHomeViewModel(repo, clock, sessions)
        vm.select(DayOfWeek.MONDAY)
        vm.startWorkout()
        assertTrue(sessions.starts.isEmpty())
    }

    @Test fun unfinishedSessionIsResumedNotRestarted() {
        sessions.put(testSession("s9", "Old", emptyList()))
        repo.refreshResult = { ApiResult.Success(ActivePlan.Some(plan)) }
        val vm = WorkoutHomeViewModel(repo, clock, sessions)
        assertEquals("s9", vm.state.value.unfinishedSession?.localId)
        vm.startWorkout()
        assertTrue(sessions.starts.isEmpty())
        assertTrue(vm.state.value.openSession)
    }

    @Test fun flaggedSessionsShowUntilDismissed() = runTest {
        sessions.put(testSession("s3", "Push", emptyList()).copy(status = SessionStatus.COMPLETED, syncError = "VALIDATION_FAILED"))
        val vm = WorkoutHomeViewModel(repo, clock, sessions)
        assertEquals(listOf("s3"), vm.state.value.flagged.map { it.localId })
        vm.dismissFlag("s3")
        assertTrue(vm.state.value.flagged.isEmpty())
    }

    @Test fun libraryReplaceModeSwapsTheSlot() = runTest {
        sessions.put(testSession("s1", "Legs", listOf(testSessionExercise(0, "squat", sessionId = "s1"))))
        val vm = ExerciseLibraryViewModel(repo, PlanBuilderStore(), sessions)
        vm.setReplacePosition(0)
        vm.pick(testExercise("lunge", "Lunge"))
        assertTrue(vm.state.value.picked)
        val e = sessions.current()!!.exercises.single()
        assertEquals("lunge", e.exerciseId)
        assertTrue(e.replaced)
        assertEquals(SessionStatus.IN_PROGRESS, sessions.current()!!.status)
    }
}
