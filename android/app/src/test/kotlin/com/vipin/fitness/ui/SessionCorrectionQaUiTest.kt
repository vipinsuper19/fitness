package com.vipin.fitness.ui

import androidx.compose.ui.test.assertIsNotEnabled
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
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.data.history.CorrectionErrors
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionCorrectionRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeWorkoutHistoryRepository
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.testExercise
import com.vipin.fitness.core.testing.testPastSession
import com.vipin.fitness.core.testing.testPastSet
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

/**
 * QA: session correction UI (#97) edge cases beyond SessionCorrectionUiTest, through the real
 * MainActivity with the Hilt fakes (FakeSessionCorrectionModule etc.), never the network.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class SessionCorrectionQaUiTest {
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

    private fun editSet(id: String) {
        scrollTo(CorrectionTags.editSet(id)); tag(CorrectionTags.editSet(id)).performClick(); waitTag(CorrectionTags.SAVE)
    }
    private fun openDetails() {
        scrollTo(CorrectionTags.EDIT_DETAILS); tag(CorrectionTags.EDIT_DETAILS).performClick(); waitTag(CorrectionTags.DIFFICULTY)
    }

    @Test fun deleteSet_removesItsRow_withOneDeleteCall() {
        openSession("s1"); editSet("cs-e1-1")
        tag(CorrectionTags.DELETE).performClick()
        until { !present(CorrectionTags.editSet("cs-e1-1")) && present(CorrectionTags.editSet("cs-e1-0")) }
        assertEquals(listOf("delete:cs-e1-1"), corrections.calls)
    }

    @Test fun lastCompletedSet_offersNoDelete() {
        val single = testPastSession("s1", listOf(testPastSet("e1", 0)), startedAt = Instant.now().minusSeconds(5400).toString())
        history.sessionResult = { ApiResult.Success(single) }; corrections.session = single
        openSession("s1"); editSet("cs-e1-0")
        assertFalse("a session needs one completed set: discard instead", present(CorrectionTags.DELETE))
    }

    @Test fun invalidWeight_disablesSave_noCall() {
        openSession("s1"); editSet("cs-e1-0")
        tag(CorrectionTags.WEIGHT).performTextReplacement(".")
        tag(CorrectionTags.SAVE).assertIsNotEnabled()
        assertTrue(corrections.calls.isEmpty())
    }

    @Test fun offlineSave_showsOffline_staysEditable_thenRetrySucceeds() {
        corrections.failure = ApiResult.Failure(ApiError.Network)
        openSession("s1"); editSet("cs-e1-0")
        tag(CorrectionTags.REPS).performTextReplacement("8"); tag(CorrectionTags.SAVE).performClick()
        waitTag(CorrectionTags.ERROR); scrollTo(CorrectionTags.ERROR)
        assertTrue(shows("offline"))
        assertTrue("offline is not a closed window", present(CorrectionTags.editSet("cs-e1-0")))
        corrections.failure = null
        editSet("cs-e1-0"); tag(CorrectionTags.REPS).performTextReplacement("8"); tag(CorrectionTags.SAVE).performClick()
        waitTag(CorrectionTags.RESULT); scrollTo(CorrectionTags.RESULT)
        assertFalse(present(CorrectionTags.ERROR))
        assertEquals(listOf("put:cs-e1-0", "put:cs-e1-0"), corrections.calls)
    }

    @Test fun sessionEmptyFromServer_showsDiscardHint() {
        corrections.failure = ApiResult.Failure(ApiError.Http(409, CorrectionErrors.SESSION_EMPTY, "empty", null))
        openSession("s1"); editSet("cs-e1-1"); tag(CorrectionTags.DELETE).performClick()
        waitTag(CorrectionTags.ERROR); scrollTo(CorrectionTags.ERROR)
        assertTrue(shows("discard the workout instead"))
        assertTrue("still editable", present(CorrectionTags.editSet("cs-e1-1")))
    }

    @Test fun positiveXpDelta_showsPlus() {
        corrections.xpDelta = 5
        openSession("s1"); editSet("cs-e1-0")
        tag(CorrectionTags.REPS).performTextReplacement("12"); tag(CorrectionTags.SAVE).performClick()
        waitTag(CorrectionTags.RESULT); scrollTo(CorrectionTags.RESULT)
        assertTrue(shows("+5 XP")); assertFalse(present(CorrectionTags.BADGES))
    }

    @Test fun editDetails_sendsOnlyTheChangedDifficulty() {
        openSession("s1"); openDetails()
        tag(CorrectionTags.DIFFICULTY).performTextReplacement("5"); tag(CorrectionTags.SAVE).performClick()
        until { corrections.calls.isNotEmpty() }
        assertEquals(listOf("completion:null:5:null"), corrections.calls)
    }

    @Test fun editDetails_unchanged_sendsNothing() {
        openSession("s1"); openDetails()
        tag(CorrectionTags.SAVE).performClick(); until { !present(CorrectionTags.DIFFICULTY) }
        assertTrue(corrections.calls.isEmpty())
    }

    @Test fun editDetails_badTime_showsHint_noCall() {
        openSession("s1"); openDetails()
        tag(CorrectionTags.TIME).performTextReplacement("25:99"); tag(CorrectionTags.SAVE).performClick()
        until { shows("Use HH:mm") }
        assertTrue(present(CorrectionTags.DIFFICULTY)); assertTrue(corrections.calls.isEmpty())
    }

    @Test fun discard_cancel_doesNothing() {
        openSession("s1"); scrollTo(CorrectionTags.DISCARD); tag(CorrectionTags.DISCARD).performClick()
        waitTag(CorrectionTags.DISCARD_CONFIRM); compose.onNode(hasText("Cancel")).performClick()
        until { !present(CorrectionTags.DISCARD_CONFIRM) }
        assertTrue(corrections.calls.isEmpty()); assertTrue(present(HistoryTags.DETAIL))
    }

    @Test fun discardFails_staysOnDetail_withError() {
        corrections.discardFailure = ApiResult.Failure(ApiError.Network)
        openSession("s1"); scrollTo(CorrectionTags.DISCARD); tag(CorrectionTags.DISCARD).performClick()
        waitTag(CorrectionTags.DISCARD_CONFIRM); tag(CorrectionTags.DISCARD_CONFIRM).performClick()
        waitTag(CorrectionTags.ERROR); scrollTo(CorrectionTags.ERROR)
        assertTrue(present(HistoryTags.DETAIL)); assertEquals(listOf("discard"), corrections.calls)
    }

    @Test fun oldSession_outsideWindow_canStillBeDiscarded() {
        openSession("old")
        scrollTo(CorrectionTags.CLOSED); assertTrue(present(CorrectionTags.CLOSED))
        assertFalse(present(CorrectionTags.editSet("cs-e1-0")))
        scrollTo(CorrectionTags.DISCARD); tag(CorrectionTags.DISCARD).performClick()
        waitTag(CorrectionTags.DISCARD_CONFIRM); tag(CorrectionTags.DISCARD_CONFIRM).performClick()
        waitTag(HistoryTags.LIST)
        assertEquals(listOf("discard"), corrections.calls)
    }
}
