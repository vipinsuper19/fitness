package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlannedExercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.FakeWorkoutSessionRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.asPlan
import com.vipin.fitness.core.testing.testExercise
import com.vipin.fitness.core.testing.testSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek

class TodayWorkoutViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeWorkoutRepository().apply { catalog["squat"] = testExercise("squat", "Squat") }
    private val sessions = FakeWorkoutSessionRepository()
    private val workout = PlannedWorkout(DayOfWeek.WEDNESDAY, "Legs", listOf(PlannedExercise("squat", 3, 8, 12)), id = "w1")
    private val plan = PlanDraft("P", 4, PlanSource.CUSTOM, null, listOf(workout)).asPlan("p1", true)

    @Test fun startsTodaysWorkoutOnceAndOpensIt() {
        val vm = TodayWorkoutViewModel(repo, sessions)
        vm.startOrResume(plan, workout)
        vm.startOrResume(plan, workout)
        assertEquals(1, sessions.starts.size)
        assertEquals(setOf("squat"), sessions.starts.single().second.keys)
        assertTrue(vm.state.value.openSession)
        assertEquals("Legs", vm.state.value.unfinished?.name)
        vm.onSessionOpened()
        assertFalse(vm.state.value.openSession)
    }

    @Test fun resumesTheUnfinishedSessionInsteadOfStarting() {
        sessions.put(testSession("s9", "Old", emptyList()))
        val vm = TodayWorkoutViewModel(repo, sessions)
        vm.startOrResume(plan, workout)
        assertTrue(sessions.starts.isEmpty())
        assertTrue(vm.state.value.openSession)
    }

    @Test fun restDayStartsNothing() {
        val vm = TodayWorkoutViewModel(repo, sessions)
        vm.startOrResume(plan, null)
        assertTrue(sessions.starts.isEmpty())
        assertFalse(vm.state.value.openSession)
    }
}
