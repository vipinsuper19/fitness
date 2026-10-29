package com.vipin.fitness.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.history.SessionHistoryPage
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionCorrectionRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeWorkoutHistoryRepository
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.testBadge
import com.vipin.fitness.core.testing.testExercise
import com.vipin.fitness.core.testing.testPastSession
import com.vipin.fitness.core.testing.testPastSet
import com.vipin.fitness.core.testing.windowClosedFailure
import com.vipin.fitness.feature.workout.CorrectionTags
import com.vipin.fitness.feature.workout.HistoryTags
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
import java.time.Instant
import javax.inject.Inject

/** Session correction (#63) from the workout history detail, through the real MainActivity. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class SessionCorrectionUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var history: FakeWorkoutHistoryRepository
    @Inject lateinit var workouts: FakeWorkoutRepository
    @Inject lateinit var corrections: FakeSessionCorrectionRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    // Finished an hour ago: inside the window whatever the zone's date is.
    private val recent = testPastSession(
        "s1", listOf(testPastSet("e1", 0), testPastSet("e1", 1)),
        startedAt = Instant.now().minusSeconds(5400).toString(),
    )
    private val old = testPastSession("old", startedAt = "2026-01-05T06:00:00Z")

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
        workouts.catalog["e1"] = testExercise("e1", "Squat")
        workouts.refreshResult = { ApiResult.Success(ActivePlan.None) }
        history.pages = { ApiResult.Success(SessionHistoryPage(listOf(recent, old), null)) }
        history.sessionResult = { id -> ApiResult.Success(if (id == "old") old else recent) }
        corrections.session = recent
    }

    @After fun tearDown() { scenario?.close() }

    private fun tag(t: String) = compose.onNodeWithTag(t)
    private fun present(t: String) = compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty()
    private fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun until(c: () -> Boolean) = compose.waitUntil(5_000) {
        Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle(); c()
    }
    private fun waitTag(t: String) = until { present(t) }

    private fun openSession(id: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("tab_WORKOUT"); tag("tab_WORKOUT").performClick(); compose.waitForIdle()
        waitTag("open_history"); tag("open_history").performScrollTo().performClick(); compose.waitForIdle()
        waitTag(HistoryTags.row(id)); tag(HistoryTags.row(id)).performClick()
        waitTag(HistoryTags.DETAIL)
    }

    private fun scrollTo(t: String) = tag(HistoryTags.DETAIL).performScrollToNode(hasTestTag(t))

    @Test fun editingASet_showsXpDeltaAndNewBadges() {
        corrections.xpDelta = -5
        corrections.newBadges = listOf(testBadge("ten", earnedAt = Instant.now()))
        openSession("s1")
        scrollTo(CorrectionTags.editSet("cs-e1-0"))
        tag(CorrectionTags.editSet("cs-e1-0")).performClick()
        waitTag(CorrectionTags.REPS)
        tag(CorrectionTags.REPS).performTextReplacement("8")
        tag(CorrectionTags.SAVE).performClick()
        scrollTo(CorrectionTags.RESULT)
        waitTag(CorrectionTags.RESULT)
        assertTrue(shows("\u22125 XP"))
        assertTrue(shows("Badge ten"))
        assertEquals(listOf("put:cs-e1-0"), corrections.calls)
        assertTrue("the corrected set shows", shows("8 × 20 kg"))
    }

    @Test fun windowClosedFromTheServer_hidesEditing() {
        corrections.failure = windowClosedFailure()
        openSession("s1")
        scrollTo(CorrectionTags.editSet("cs-e1-1"))
        tag(CorrectionTags.editSet("cs-e1-1")).performClick()
        waitTag(CorrectionTags.DELETE)
        tag(CorrectionTags.DELETE).performClick()
        scrollTo(CorrectionTags.ERROR)
        waitTag(CorrectionTags.ERROR)
        assertFalse(present(CorrectionTags.editSet("cs-e1-0")))
        assertTrue(present(CorrectionTags.CLOSED))
    }

    @Test fun oldSession_isReadOnlyButCanBeDiscarded() {
        openSession("old")
        scrollTo(CorrectionTags.CLOSED)
        assertFalse(present(CorrectionTags.EDIT_DETAILS))
        assertFalse(present(CorrectionTags.editSet("cs-e1-0")))
        assertTrue(present(CorrectionTags.DISCARD))
    }

    @Test fun discard_confirmsThenReturnsToAReloadedHistory() {
        openSession("s1")
        val before = history.historyCalls.size
        scrollTo(CorrectionTags.DISCARD)
        tag(CorrectionTags.DISCARD).performClick()
        waitTag(CorrectionTags.DISCARD_CONFIRM)
        tag(CorrectionTags.DISCARD_CONFIRM).performClick()
        waitTag(HistoryTags.LIST)
        until { history.historyCalls.size > before }
        assertEquals(listOf("discard"), corrections.calls)
    }
}
