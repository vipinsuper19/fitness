package com.vipin.fitness.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.history.SessionHistoryPage
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.FakeWorkoutHistoryRepository
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.testExercise
import com.vipin.fitness.core.testing.testPastSession
import com.vipin.fitness.core.testing.testPastSet
import com.vipin.fitness.feature.workout.HistoryTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlannedExercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.testing.asPlan
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/**
 * QA (#71): workout history list and past-session detail through the real MainActivity, navigation
 * and ViewModels; history comes from FakeWorkoutHistoryModule (no network).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class WorkoutHistoryUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var history: FakeWorkoutHistoryRepository
    @Inject lateinit var workouts: FakeWorkoutRepository
    @Inject lateinit var units: FakeUnitPreference
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
        workouts.catalog["e1"] = testExercise("e1", "Squat")
        workouts.catalog["e2"] = testExercise("e2", "Bench press")
        // With an active plan (the no-plan case is covered below; it was bug #87).
        workouts.refreshResult = { ApiResult.Success(ActivePlan.Some(plan)) }
    }
    private val plan = PlanDraft(
        "P", 4, PlanSource.CUSTOM, null,
        listOf(PlannedWorkout(DayOfWeek.WEDNESDAY, "Legs", listOf(PlannedExercise("e1", 3, 8, 12)), id = "w1")),
    ).asPlan("p1", true)
    @After fun tearDown() { scenario?.close() }

    private fun tag(t: String) = compose.onNodeWithTag(t)
    private fun present(t: String) = compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty()
    private fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    /** Also gives real time to work on Dispatchers.IO and drains the main looper (waitUntil runs on a virtual clock). */
    private fun until(c: () -> Boolean) = compose.waitUntil(5_000) {
        Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle(); c()
    }
    private fun waitTag(t: String) = until { present(t) }
    private fun waitText(t: String) = until { shows(t) }
    private val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private fun local(iso: String, zone: String) = fmt.format(Instant.parse(iso).atZone(ZoneId.of(zone)))
    private fun pages(vararg p: Pair<String?, SessionHistoryPage>) { val m = p.toMap(); history.pages = { c -> m[c]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.Network) } }

    private fun openHistory() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("tab_WORKOUT"); tag("tab_WORKOUT").performClick(); compose.waitForIdle()
        waitTag("open_history"); tag("open_history").performScrollTo().performClick(); compose.waitForIdle()
    }

    @Test fun noActivePlan_userCanStillOpenHistory() {
        workouts.refreshResult = { ApiResult.Success(ActivePlan.None) }
        openHistory()
        waitTag(HistoryTags.EMPTY)
    }

    @Test fun emptyHistory_showsEmptyState() {
        openHistory()
        waitTag(HistoryTags.EMPTY)
        assertTrue(shows("No workouts yet"))
        assertEquals(listOf<String?>(null), history.historyCalls)
    }

    @Test fun list_showsRowsWithSetCountAndActiveTime() {
        pages(null to SessionHistoryPage(listOf(
            testPastSession("s1", sets = listOf(testPastSet("e1", 0), testPastSet("e1", 1), testPastSet("e1", 2, completed = false))),
            testPastSession("s2", startedAt = "2026-10-18T06:00:00Z"),
        ), null))
        openHistory()
        waitTag(HistoryTags.row("s1"))
        assertTrue(present(HistoryTags.row("s2")))
        assertTrue("2 completed sets of 3", shows("2 sets"))
        assertTrue(shows("1 set"))
        assertTrue(shows("Active time: 25:00"))
    }

    @Test fun scrollingToTheEnd_loadsNextPage_withoutDuplicates() {
        val first = (1..20).map { testPastSession("a$it") }
        pages(null to SessionHistoryPage(first, "c2"), "c2" to SessionHistoryPage(listOf(testPastSession("a20"), testPastSession("b1")), null))
        openHistory()
        waitTag(HistoryTags.LIST)
        tag(HistoryTags.LIST).performScrollToNode(hasTestTag(HistoryTags.row("a20")))
        until { history.historyCalls.size == 2 }
        tag(HistoryTags.LIST).performScrollToNode(hasTestTag(HistoryTags.row("b1")))
        assertEquals(listOf(null, "c2"), history.historyCalls)
        assertEquals(1, compose.onAllNodes(hasTestTag(HistoryTags.row("a20"))).fetchSemanticsNodes().size)
    }

    @Test fun nextPageFailure_keepsList_andRetryLoadsIt() {
        var fail = true
        history.pages = { c ->
            when {
                c == null -> ApiResult.Success(SessionHistoryPage(listOf(testPastSession("a1")), "c2"))
                fail -> ApiResult.Failure(ApiError.Network)
                else -> ApiResult.Success(SessionHistoryPage(listOf(testPastSession("b1")), null))
            }
        }
        openHistory()
        waitTag(HistoryTags.MORE_RETRY)
        assertTrue(present(HistoryTags.row("a1")))
        fail = false
        tag(HistoryTags.LIST).performScrollToNode(hasTestTag(HistoryTags.MORE_RETRY))
        tag(HistoryTags.MORE_RETRY).performClick()
        waitTag(HistoryTags.row("b1"))
    }

    @Test fun firstPageOffline_showsError_andRetryRecovers() {
        var fail = true
        history.pages = { if (fail) ApiResult.Failure(ApiError.Network) else ApiResult.Success(SessionHistoryPage(listOf(testPastSession("s1")), null)) }
        openHistory()
        waitTag(HistoryTags.ERROR)
        assertTrue(shows("You're offline"))
        fail = false
        tag(HistoryTags.RETRY).performClick()
        waitTag(HistoryTags.row("s1"))
    }

    @Test fun openingARow_showsPastSessionDetail_groupedByExercise() {
        val s = testPastSession("s1", sets = listOf(testPastSet("e1", 0), testPastSet("e1", 1, reps = 8, weightKg = 22.5), testPastSet("e2", 0, completed = false)))
        pages(null to SessionHistoryPage(listOf(s), null))
        history.sessionResult = { ApiResult.Success(s) }
        openHistory()
        waitTag(HistoryTags.row("s1")); tag(HistoryTags.row("s1")).performClick()
        waitTag(HistoryTags.DETAIL)
        waitText("Squat")
        assertTrue(shows("Bench press"))
        assertTrue(shows("10 × 20 kg")); assertTrue(shows("8 × 22.5 kg")); assertTrue(shows("skipped"))
        assertTrue(shows("Difficulty: 3/5"))
        assertEquals(3, compose.onAllNodes(hasTestTag(HistoryTags.SET)).fetchSemanticsNodes().size)
    }

    @Test fun removedSession_detailSaysNoLongerAvailable_withoutRetry() {
        pages(null to SessionHistoryPage(listOf(testPastSession("gone")), null))
        history.sessionResult = { ApiResult.Failure(ApiError.Http(404, "NOT_FOUND", "nope", null)) }
        openHistory()
        waitTag(HistoryTags.row("gone")); tag(HistoryTags.row("gone")).performClick()
        waitText("This workout is no longer available.")
        assertTrue(!present(HistoryTags.RETRY))
    }

    @Test fun timesUseTheProfileTimezone_notTheDefault() {
        // 2026-10-20T06:30Z = 12:00 IST, 02:30 in New York.
        profiles.profile = profiles.profile.copy(timezone = "America/New_York")
        val s = testPastSession("s1", startedAt = "2026-10-20T06:00:00Z")
        pages(null to SessionHistoryPage(listOf(s), null)); history.sessionResult = { ApiResult.Success(s) }
        openHistory()
        waitText(local("2026-10-20T06:30:00Z", "America/New_York"))
        assertTrue(!shows(local("2026-10-20T06:30:00Z", "Asia/Kolkata")))
        tag(HistoryTags.row("s1")).performClick()
        waitTag(HistoryTags.DETAIL)
        waitText(local("2026-10-20T06:30:00Z", "America/New_York"))
    }

    @Test fun unknownProfileTimezone_fallsBackToIst() {
        profiles.profile = profiles.profile.copy(timezone = "Mars/Olympus")
        pages(null to SessionHistoryPage(listOf(testPastSession("s1", startedAt = "2026-10-20T06:00:00Z")), null))
        openHistory()
        waitText(local("2026-10-20T06:30:00Z", "Asia/Kolkata"))
    }

    @Test fun imperialUnits_showPoundsInDetail() {
        units.units.value = UnitSystem.IMPERIAL
        val s = testPastSession("s1", sets = listOf(testPastSet("e1", 0), testPastSet("e1", 1, reps = null, weightKg = 22.5)))
        pages(null to SessionHistoryPage(listOf(s), null)); history.sessionResult = { ApiResult.Success(s) }
        openHistory()
        waitTag(HistoryTags.row("s1")); tag(HistoryTags.row("s1")).performClick()
        waitText("10 × 44.1 lb")
        assertTrue(shows("49.6 lb"))
        assertTrue(!shows(" kg"))
    }
}
