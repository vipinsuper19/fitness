package com.vipin.fitness.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.feature.workout.PlanAction
import com.vipin.fitness.core.data.workout.PlanStatus
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakePlanManagementRepository
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.testPlanSummary
import com.vipin.fitness.feature.workout.PlansTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/** My plans (#102) through the real MainActivity, with the in-memory plan repository. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class PlansUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var workouts: FakeWorkoutRepository
    @Inject lateinit var plans: FakePlanManagementRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
        workouts.refreshResult = { ApiResult.Success(ActivePlan.None) }
        plans.plans += listOf(
            testPlanSummary("a", PlanStatus.ACTIVE, "Strength A"),
            testPlanSummary("b", PlanStatus.INACTIVE, "Cardio B"),
            testPlanSummary("c", PlanStatus.ARCHIVED, "Old C"),
        )
    }

    @After fun tearDown() { scenario?.close() }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun present(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun until(c: () -> Boolean) = compose.waitUntil(5_000) {
        Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle(); c()
    }
    private fun waitTag(t: String) = until { present(t) }
    /** Scrolls when the node is inside a scrollable; chips and buttons outside one are left as they are. */
    private fun scrollTo(t: String) { try { tag(t).performScrollTo() } catch (_: AssertionError) {} }
    private fun click(t: String) { waitTag(t); scrollTo(t); tag(t).performClick(); compose.waitForIdle() }
    private fun act(id: String, a: PlanAction) = click(PlansTags.action(id, a))
    private fun filter(s: PlanStatus?) = click(PlansTags.filter(s))

    private fun openPlans() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("tab_WORKOUT"); tag("tab_WORKOUT").performClick(); compose.waitForIdle()
        click("open_plans")
    }

    @Test fun listHidesArchivedAndShowsWeekAndSessions() {
        openPlans()
        waitTag(PlansTags.row("a"))
        assertTrue(present(PlansTags.row("b")))
        assertFalse(present(PlansTags.row("c")))
        assertTrue(shows("Strength A")); assertTrue(shows("Week 2 of 8")); assertTrue(shows("3 sessions done"))
        // Actions follow the status.
        assertTrue(present(PlansTags.action("a", PlanAction.PAUSE)))
        assertTrue(present(PlansTags.action("b", PlanAction.MAKE_ACTIVE)))
        assertFalse(present(PlansTags.action("b", PlanAction.RESTART)))
        assertEquals(null, plans.listCalls.first().first)
    }

    @Test fun filtersAskTheServerAndShowEmpty() {
        openPlans(); waitTag(PlansTags.row("a"))
        filter(PlanStatus.ARCHIVED)
        waitTag(PlansTags.row("c"))
        assertFalse(present(PlansTags.row("a")))
        assertTrue(present(PlansTags.action("c", PlanAction.UNARCHIVE)))
        assertEquals(PlanStatus.ARCHIVED, plans.listCalls.last().first)
        filter(PlanStatus.PAUSED)
        waitTag(PlansTags.EMPTY)
        assertEquals(PlanStatus.PAUSED, plans.listCalls.last().first)
        filter(null)
        waitTag(PlansTags.row("a"))
        assertEquals(null, plans.listCalls.last().first)
    }

    @Test fun loadErrorThenRetry() {
        plans.pages = { _, _ -> FAKE_NETWORK_FAILURE }
        openPlans()
        waitTag(PlansTags.ERROR)
        plans.pages = null
        click(PlansTags.RETRY)
        waitTag(PlansTags.row("a"))
    }

    @Test fun renameTrimsAndRejectsBlank() {
        openPlans(); act("b", PlanAction.RENAME)
        waitTag(PlansTags.RENAME_FIELD)
        tag(PlansTags.RENAME_FIELD).performTextReplacement("   ")
        click(PlansTags.RENAME_SAVE)
        waitTag(PlansTags.RENAME_ERROR)
        assertTrue(shows("Enter a name (1–80 characters)."))
        assertTrue(plans.calls.none { it.startsWith("rename") })
        tag(PlansTags.RENAME_FIELD).performTextReplacement("  Leg day  ")
        click(PlansTags.RENAME_SAVE)
        until { !present(PlansTags.RENAME_FIELD) && shows("Leg day") }
        assertEquals("Leg day", plans.plans.first { it.id == "b" }.name)
        assertTrue(plans.calls.single { it.startsWith("rename") }.startsWith("rename:b:"))
    }

    @Test fun renamingAPlanArchivedElsewhereShowsTheNoticeAndRefresh() {
        openPlans(); act("b", PlanAction.RENAME)
        waitTag(PlansTags.RENAME_FIELD)
        plans.failure = ApiResult.Failure(ApiError.Http(409, "PLAN_ARCHIVED", "archived", null))
        tag(PlansTags.RENAME_FIELD).performTextReplacement("New name")
        click(PlansTags.RENAME_SAVE)
        waitTag(PlansTags.ARCHIVED_NOTICE)
        assertFalse(present(PlansTags.RENAME_FIELD))
        plans.failure = null
        click(PlansTags.REFRESH)
        until { !present(PlansTags.ARCHIVED_NOTICE) }
    }

    @Test fun archiveMovesThePlanToArchivedAndUnarchiveBringsItBackInactive() {
        openPlans(); act("b", PlanAction.ARCHIVE)
        until { !present(PlansTags.row("b")) }
        filter(PlanStatus.ARCHIVED)
        waitTag(PlansTags.row("b"))
        val lists = plans.listCalls.size
        act("b", PlanAction.UNARCHIVE)
        // Never paused, so it comes back inactive, without a reload.
        until { plans.calls.size == 2 }
        assertEquals(PlanStatus.INACTIVE, plans.plans.first { it.id == "b" }.status)
        assertEquals(lists, plans.listCalls.size)
        assertEquals(listOf("archive", "unarchive"), plans.calls.map { it.substringBefore(':') })
        filter(PlanStatus.INACTIVE)
        waitTag(PlansTags.action("b", PlanAction.MAKE_ACTIVE))
    }

    @Test fun unarchiveUnderTheArchivedFilterDropsTheRow() {
        openPlans(); filter(PlanStatus.ARCHIVED)
        act("c", PlanAction.UNARCHIVE)
        until { !present(PlansTags.row("c")) }
        waitTag(PlansTags.EMPTY)
    }

    @Test fun unarchivingAPlanArchivedWhilePausedReturnsItPaused() {
        plans.plans[2] = plans.plans[2].copy(pausedAt = FakePlanManagementRepository.PAUSED_AT)
        openPlans(); filter(PlanStatus.ARCHIVED)
        act("c", PlanAction.UNARCHIVE)
        until { plans.plans[2].status == PlanStatus.PAUSED }
        filter(PlanStatus.PAUSED)
        waitTag(PlansTags.action("c", PlanAction.RESUME))
    }

    @Test fun restartResetsTheWeek() {
        openPlans(); waitTag(PlansTags.row("a"))
        assertTrue(shows("Week 2 of 8"))
        act("a", PlanAction.RESTART)
        until { shows("Week 1 of 8") }
        assertFalse(shows("Week 2 of 8"))
        assertEquals(PlanStatus.ACTIVE, plans.plans[0].status)
        assertTrue(plans.calls.any { it.startsWith("restart:a:") })
    }

    @Test fun pauseThenResume() {
        openPlans(); act("a", PlanAction.PAUSE)
        waitTag(PlansTags.action("a", PlanAction.RESUME))
        act("a", PlanAction.RESUME)
        waitTag(PlansTags.action("a", PlanAction.PAUSE))
        assertEquals(listOf("pause", "resume"), plans.calls.map { it.substringBefore(':') })
    }

    @Test fun makeActivePausesThePreviouslyActivePlan() {
        openPlans(); act("b", PlanAction.MAKE_ACTIVE)
        waitTag(PlansTags.action("b", PlanAction.PAUSE))
        waitTag(PlansTags.action("a", PlanAction.RESUME))
        assertEquals(PlanStatus.PAUSED, plans.plans[0].status)
    }

    @Test fun offlineActionShowsTheMessageAndChangesNothing() {
        openPlans(); waitTag(PlansTags.row("a"))
        plans.failure = FAKE_NETWORK_FAILURE
        act("a", PlanAction.PAUSE)
        until { shows("You're offline. Connect to manage your plans.") }
        assertEquals(PlanStatus.ACTIVE, plans.plans[0].status)
        assertTrue(present(PlansTags.action("a", PlanAction.PAUSE)))
    }
}
