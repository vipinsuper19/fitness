package com.vipin.fitness.ui

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.ExerciseProgress
import com.vipin.fitness.core.data.ExerciseProgressPage
import com.vipin.fitness.core.data.ExerciseProgressPoint
import com.vipin.fitness.core.data.PersonalRecords
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.SummaryBucket
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeStrengthProgressRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.testExerciseSummary
import com.vipin.fitness.feature.progress.StrengthTags
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
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Strength progress (#114): Progress → Strength tab, list and exercise detail, through the real MainActivity. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class StrengthUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var units: FakeUnitPreference
    @Inject lateinit var strength: FakeStrengthProgressRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    private val at = Instant.parse("2026-10-20T10:00:00Z") // 20 Oct in Asia/Kolkata
    private val pr = PersonalRecords(maxWeightKg = 100.0, maxWeightKgAchievedAt = at, best1RmKg = 112.5, maxReps = 12)

    /** Newest period (= to) logged, oldest (= from) empty. */
    private fun detailOf(id: String, from: LocalDate, to: LocalDate, b: SummaryBucket) = ApiResult.Success(
        ExerciseProgress(
            id, "Squat", TrackingType.REPS_WEIGHT, from, to, b, pr,
            listOf(
                ExerciseProgressPoint(from, sessions = 0, sets = 0),
                ExerciseProgressPoint(to, sessions = 1, sets = 3, volumeKg = 2400.0, totalReps = 24, topSetWeightKg = 100.0),
            ),
        ),
    )

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
        strength.pages[null] = ExerciseProgressPage(listOf(testExerciseSummary("sq", "Squat", pr), testExerciseSummary("bp", "Bench press")), null)
        strength.detail = { id, f, t, b -> detailOf(id, f, t, b) }
    }

    @After fun tearDown() { scenario?.close() }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun present(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun until(c: () -> Boolean) = compose.waitUntil(5_000) {
        Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle(); c()
    }
    private fun waitTag(t: String) = until { present(t) }
    private fun click(t: String) { waitTag(t); tag(t).performClick(); compose.waitForIdle() }
    /** Scrolls the (single) scrollable list to [t]; false while it isn't there yet. */
    private fun scrolledTo(t: String): Boolean {
        if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size != 1) return present(t)
        try { compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(t)) } catch (_: AssertionError) { return false }
        return present(t)
    }

    private fun openStrength() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("tab_PROGRESS"); click(StrengthTags.TAB)
    }
    private fun lastDetail() = strength.detailCalls.last().split(":").let { Triple(LocalDate.parse(it[1]), LocalDate.parse(it[2]), it[3]) }

    @Test fun listShowsExercisesWithSessionsAndRecords() {
        openStrength()
        waitTag(StrengthTags.row("sq"))
        assertTrue(present(StrengthTags.LIST)); assertTrue(present(StrengthTags.row("bp")))
        assertTrue(shows("Squat")); assertTrue(shows("Bench press"))
        assertTrue(shows("1 session · last 20 Oct 2026"))
        assertTrue(shows("Top 100 kg · 1RM 112.5 kg · 12 reps"))
        // Last 90 days, ending today, first page.
        val (from, to, cursor) = strength.listCalls.single()
        assertEquals(89, ChronoUnit.DAYS.between(from, to)); assertEquals(null, cursor)
    }

    @Test fun noExercisesShowsEmpty() {
        strength.pages[null] = ExerciseProgressPage(emptyList(), null)
        openStrength()
        waitTag(StrengthTags.EMPTY)
        assertTrue(shows("No strength sets in the last 90 days."))
    }

    @Test fun listErrorThenRetry() {
        strength.pages.clear()
        openStrength()
        waitTag(StrengthTags.ERROR)
        assertTrue(shows("You're offline. Check your connection."))
        strength.pages[null] = ExerciseProgressPage(listOf(testExerciseSummary("sq", "Squat")), null)
        click(StrengthTags.RETRY)
        waitTag(StrengthTags.row("sq"))
        assertFalse(present(StrengthTags.ERROR))
    }

    @Test fun pagingFailsThenMoreRetryAppendsWithoutDuplicates() {
        strength.pages[null] = ExerciseProgressPage(listOf(testExerciseSummary("sq", "Squat"), testExerciseSummary("bp", "Bench press")), "c2")
        openStrength()
        until { scrolledTo(StrengthTags.MORE_RETRY) }
        assertEquals(listOf(null, "c2"), strength.listCalls.map { it.third })
        strength.pages["c2"] = ExerciseProgressPage(listOf(testExerciseSummary("bp", "Bench press"), testExerciseSummary("dl", "Deadlift")), null)
        click(StrengthTags.MORE_RETRY)
        until { scrolledTo(StrengthTags.row("dl")) }
        assertFalse(present(StrengthTags.MORE_RETRY))
        assertEquals(1, compose.onAllNodes(hasTestTag(StrengthTags.row("bp")), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(3, strength.listCalls.size)
    }

    @Test fun detailShowsRecordsAndPointsPerDate() {
        openStrength(); click(StrengthTags.row("sq"))
        waitTag(StrengthTags.PR)
        assertTrue(present(StrengthTags.DETAIL))
        assertTrue(shows("All-time records"))
        assertTrue(shows("Heaviest: 100 kg (20 Oct 2026)"))
        assertTrue(shows("Best est. 1RM: 112.5 kg")); assertTrue(shows("Most reps: 12"))
        val (from, to, b) = lastDetail()
        assertEquals("week", b)
        assertEquals(to.minusWeeks(26).plusDays(1), from)
        assertTrue(strength.detailCalls.last().startsWith("sq:"))
        until { scrolledTo(StrengthTags.point(to.toString())) }
        assertTrue(shows("3 sets · Volume 2,400 kg · 24 reps · Top 100 kg"))
        until { scrolledTo(StrengthTags.point(from.toString())) }
        assertTrue(shows("No sets"))
    }

    @Test fun bucketSwitchingUsesEachRange() {
        openStrength(); click(StrengthTags.row("sq")); waitTag(StrengthTags.PR)
        click(StrengthTags.bucket(SummaryBucket.DAY))
        until { strength.detailCalls.size == 2 }
        lastDetail().let { (f, t, b) -> assertEquals("day", b); assertEquals(29, ChronoUnit.DAYS.between(f, t)) }
        until { scrolledTo(StrengthTags.point(lastDetail().second.toString())) }
        click(StrengthTags.bucket(SummaryBucket.MONTH))
        until { strength.detailCalls.size == 3 }
        lastDetail().let { (f, t, b) -> assertEquals("month", b); assertEquals(365, ChronoUnit.DAYS.between(f, t)) }
        until { scrolledTo(StrengthTags.point(lastDetail().first.toString())) }
        // Re-selecting the current bucket doesn't reload.
        click(StrengthTags.bucket(SummaryBucket.MONTH))
        assertEquals(3, strength.detailCalls.size)
    }

    @Test fun backReturnsToTheListWithoutReloading() {
        openStrength(); click(StrengthTags.row("sq")); waitTag(StrengthTags.PR)
        click(StrengthTags.bucket(SummaryBucket.MONTH)); until { strength.detailCalls.size == 2 }
        click(StrengthTags.DETAIL_BACK)
        waitTag(StrengthTags.row("sq"))
        assertFalse(present(StrengthTags.DETAIL))
        assertEquals(1, strength.listCalls.size)
        click(StrengthTags.row("bp"))
        until { strength.detailCalls.size == 3 }
        assertTrue(strength.detailCalls.last().startsWith("bp:"))
        // The new exercise opens on Week, not the Month picked before Back.
        assertEquals("week", lastDetail().third)
        waitTag(StrengthTags.PR)
    }

    @Test fun openingAnExerciseAlwaysStartsOnWeek() {
        openStrength(); click(StrengthTags.row("sq")); waitTag(StrengthTags.PR)
        click(StrengthTags.bucket(SummaryBucket.MONTH)); until { strength.detailCalls.size == 2 }
        tag(StrengthTags.bucket(SummaryBucket.MONTH)).assertIsSelected()
        click(StrengthTags.DETAIL_BACK); waitTag(StrengthTags.row("sq"))
        // Same exercise: Week again.
        click(StrengthTags.row("sq")); until { strength.detailCalls.size == 3 }; waitTag(StrengthTags.PR)
        assertEquals("week", lastDetail().third)
        tag(StrengthTags.bucket(SummaryBucket.WEEK)).assertIsSelected()
        tag(StrengthTags.bucket(SummaryBucket.MONTH)).assertIsNotSelected()
        // Another exercise after Month: Week too.
        click(StrengthTags.bucket(SummaryBucket.MONTH)); until { strength.detailCalls.size == 4 }
        click(StrengthTags.DETAIL_BACK); waitTag(StrengthTags.row("bp"))
        click(StrengthTags.row("bp")); until { strength.detailCalls.size == 5 }; waitTag(StrengthTags.PR)
        assertTrue(strength.detailCalls.last().startsWith("bp:")); assertEquals("week", lastDetail().third)
        tag(StrengthTags.bucket(SummaryBucket.WEEK)).assertIsSelected()
    }

    @Test fun detailErrorThenRetry() {
        strength.detail = { _, _, _, _ -> FAKE_NETWORK_FAILURE }
        openStrength(); click(StrengthTags.row("sq"))
        waitTag(StrengthTags.DETAIL_ERROR)
        assertTrue(shows("You're offline. Check your connection."))
        strength.detail = { id, f, t, b -> detailOf(id, f, t, b) }
        click(StrengthTags.DETAIL_RETRY)
        waitTag(StrengthTags.PR)
        assertFalse(present(StrengthTags.DETAIL_ERROR))
        assertEquals(2, strength.detailCalls.size)
    }

    @Test fun deletedExerciseShowsNotFoundWithoutRetry() {
        strength.detail = { _, _, _, _ -> ApiResult.Failure(ApiError.Http(404, "NOT_FOUND", "gone", null)) }
        openStrength(); click(StrengthTags.row("sq"))
        waitTag(StrengthTags.DETAIL_ERROR)
        assertTrue(shows("This exercise no longer exists."))
        assertFalse(present(StrengthTags.DETAIL_RETRY))
        assertTrue(present(StrengthTags.DETAIL_BACK))
    }

    @Test fun imperialUnits() {
        units.units.value = UnitSystem.IMPERIAL
        openStrength(); waitTag(StrengthTags.row("sq"))
        assertTrue(shows("Top 220.5 lb · 1RM 248 lb · 12 reps"))
        click(StrengthTags.row("sq")); waitTag(StrengthTags.PR)
        assertTrue(shows("Heaviest: 220.5 lb (20 Oct 2026)")); assertTrue(shows("Best est. 1RM: 248 lb"))
        until { scrolledTo(StrengthTags.point(lastDetail().second.toString())) }
        assertTrue(shows("Volume 5,291 lb")); assertTrue(shows("Top 220.5 lb"))
    }
}
