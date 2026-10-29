package com.vipin.fitness.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * QA test-first for #76 (goals & milestones, still OPEN). Tags/strings from feat/android-goals.
 * When #76 merges: inject FakeGoalsRepository (FakeGoalsModule) instead of PendingGoalsSeed,
 * seed weights via FakeProgressRepository, and drop the @Ignore.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class GoalsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    private val goals = PendingGoalsSeed() // TODO(#76): @Inject lateinit var goals: FakeGoalsRepository
    private val ui by lazy { UiHelpers(compose) }
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
    }
    @After fun tearDown() { scenario?.close() }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); ui.waitTag("tab_PROGRESS") }
    private fun openGoals() {
        launch(); ui.tag("tab_PROGRESS").performClick(); compose.waitForIdle()
        ui.waitTag(GoalsTags.TAB); ui.tag(GoalsTags.TAB).performClick(); compose.waitForIdle()
    }
    private fun lose(id: String = "g1", status: String = "active") = GoalRow(id, "lose_weight", status, 70.0, 80.0, null)

    @Ignore("Pending #76") @Test fun noGoals_showsEmptyState() {
        openGoals(); ui.waitTag(GoalsTags.EMPTY)
        assertTrue(ui.shows("No goals yet"))
    }

    @Ignore("Pending #76") @Test fun loseWeight_halfway_showsBarAnd50Milestone() {
        goals.goals += lose(); goals.latestWeightKg = 75.0
        openGoals(); ui.waitTag(GoalsTags.card("g1"))
        assertTrue(ui.present(GoalsTags.bar("g1")))
        ui.waitTag(GoalsTags.milestone("g1"))
        assertTrue(ui.shows("Milestone: 50% of the way there"))
    }

    @Ignore("Pending #76") @Test fun progressPastTarget_clampsTo100() {
        goals.goals += lose(); goals.latestWeightKg = 65.0
        openGoals(); ui.waitText("Milestone: 100%")
    }

    @Ignore("Pending #76") @Test fun noWeightData_showsHint_noBar() {
        goals.goals += lose()
        openGoals(); ui.waitText("Log your weight in Measurements")
        assertFalse(ui.present(GoalsTags.bar("g1")))
    }

    @Ignore("Pending #76") @Test fun unknownTypeOrStatus_stillListed() {
        goals.goals += GoalRow("g9", "future_type", "active", null, null, null)
        openGoals(); ui.waitTag(GoalsTags.card("g9"))
    }

    @Ignore("Pending #76") @Test fun add_validatesWeightRange_thenCreatesOnce() {
        openGoals(); ui.waitTag(GoalsTags.ADD); ui.tag(GoalsTags.ADD).performClick()
        ui.waitTag(GoalsTags.type("lose_weight")); ui.tag(GoalsTags.type("lose_weight")).performClick()
        ui.tag(GoalsTags.TARGET).performTextInput("19")
        ui.tag(GoalsTags.SAVE).performClick(); ui.waitText("between 20 and 400 kg")
        assertTrue(goals.writes.isEmpty())
        ui.tag(GoalsTags.TARGET).performTextClearance(); ui.tag(GoalsTags.TARGET).performTextInput("70")
        ui.tag(GoalsTags.SAVE).performClick()
        ui.until { goals.writes.size == 1 }
        assertEquals("create", goals.writes.single().first)
    }

    @Ignore("Pending #76") @Test fun pastDate_rejected() {
        openGoals(); ui.waitTag(GoalsTags.ADD); ui.tag(GoalsTags.ADD).performClick()
        ui.waitTag(GoalsTags.type("lose_weight")); ui.tag(GoalsTags.type("lose_weight")).performClick()
        ui.tag(GoalsTags.TARGET).performTextInput("70"); ui.tag(GoalsTags.DATE).performTextInput("2000-01-01")
        ui.tag(GoalsTags.SAVE).performClick(); ui.waitText("today or later")
        assertTrue(goals.writes.isEmpty())
    }

    @Ignore("Pending #76") @Test fun workoutsPerWeek_outOfRange_rejected() {
        openGoals(); ui.waitTag(GoalsTags.ADD); ui.tag(GoalsTags.ADD).performClick()
        ui.waitTag(GoalsTags.type("workouts_per_week")); ui.tag(GoalsTags.type("workouts_per_week")).performClick()
        ui.tag(GoalsTags.TARGET).performTextInput("8"); ui.tag(GoalsTags.SAVE).performClick()
        ui.waitText("whole number from 1 to 7")
    }

    @Ignore("Pending #76") @Test fun createFails_retryReusesIdempotencyKey() {
        goals.failNextWrite = true
        openGoals(); ui.waitTag(GoalsTags.ADD); ui.tag(GoalsTags.ADD).performClick()
        ui.waitTag(GoalsTags.type("lose_weight")); ui.tag(GoalsTags.type("lose_weight")).performClick()
        ui.tag(GoalsTags.TARGET).performTextInput("70"); ui.tag(GoalsTags.SAVE).performClick()
        ui.waitTag(GoalsTags.WRITE_ERROR)
        ui.tag(GoalsTags.SAVE).performClick(); ui.until { goals.writes.size == 2 }
        assertEquals(goals.writes[0].second, goals.writes[1].second)
    }

    @Ignore("Pending #76") @Test fun markAchieved_movesToPastGoals_thenReactivate() {
        goals.goals += lose()
        openGoals(); ui.waitTag(GoalsTags.achieved("g1")); ui.tag(GoalsTags.achieved("g1")).performClick()
        ui.waitText("Past goals"); ui.waitTag(GoalsTags.reactivate("g1"))
        assertEquals("update:g1", goals.writes.last().first)
        ui.tag(GoalsTags.reactivate("g1")).performScrollTo().performClick()
        ui.waitTag(GoalsTags.achieved("g1"))
    }

    @Ignore("Pending #76") @Test fun delete_removesCard() {
        goals.goals += lose()
        openGoals(); ui.waitTag(GoalsTags.delete("g1")); ui.tag(GoalsTags.delete("g1")).performClick()
        ui.until { !ui.present(GoalsTags.card("g1")) }
        assertEquals("delete:g1", goals.writes.single().first)
    }

    @Ignore("Pending #76") @Test fun listFails_goalsTabShowsErrorNotSpinnerForever() {
        goals.listFails = true
        openGoals(); ui.waitText("try again")
    }

    @Ignore("Pending #76") @Test fun home_goalsSlot_showsActiveGoalWithProgress() {
        goals.goals += lose(); goals.goals += lose("g2", status = "achieved"); goals.latestWeightKg = 75.0
        launch(); ui.waitTag(GoalsTags.HOME_SLOT)
        ui.waitTag(GoalsTags.homeBar("g1"))
        assertFalse("achieved goals not on Home", ui.present(GoalsTags.homeBar("g2")))
    }
}
