package com.vipin.fitness.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlannedExercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.FakeWorkoutSessionRepository
import com.vipin.fitness.core.testing.asPlan
import com.vipin.fitness.core.testing.testSession
import com.vipin.fitness.feature.home.HomeContent
import com.vipin.fitness.feature.home.HomeState
import com.vipin.fitness.feature.home.PlanDay
import com.vipin.fitness.feature.home.Slot
import com.vipin.fitness.feature.workout.TodayWorkoutViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate

/** T13's start/resume card plugged into Home through planDayContent and extraSlots only. */
@RunWith(AndroidJUnit4::class)
class HomeWorkoutSlotsTest {
    @get:Rule val compose = createComposeRule()
    private val sessions = FakeWorkoutSessionRepository()
    private val vm = TodayWorkoutViewModel(FakeWorkoutRepository(), sessions)
    private val workout = PlannedWorkout(DayOfWeek.WEDNESDAY, "Legs", listOf(PlannedExercise("squat", 3, 8, 12)), id = "w1")
    private val plan = PlanDraft("P", 4, PlanSource.CUSTOM, null, listOf(workout)).asPlan("p1", true)
    private var opened = 0

    private fun show(day: PlanDay) = compose.setContent {
        HomeContent(
            HomeState(today = LocalDate.of(2026, 10, 21), plan = Slot.Ready(day)),
            onRetry = {},
            planDayContent = { WorkoutPlanDayContent(it, { opened++ }, vm) },
            extraSlots = { workoutHomeSlots(it, { opened++ }) { vm } },
        )
    }

    @Test fun startFromHomeStartsAndOpensTheSession() {
        show(PlanDay(plan, workout))
        compose.onNodeWithTag("home_start_workout").performClick()
        compose.waitForIdle()
        assertEquals(1, sessions.starts.size)
        assertEquals(1, opened)
        compose.onNodeWithTag("home_resume_workout").assertIsDisplayed()
    }

    @Test fun restDayOffersResumeOfAnUnfinishedSession() {
        sessions.put(testSession("s1", "Push", emptyList()))
        show(PlanDay(plan, null))
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_unfinished_workout"))
        compose.onNodeWithTag("home_unfinished_workout").performClick()
        assertEquals(1, opened)
    }
}
