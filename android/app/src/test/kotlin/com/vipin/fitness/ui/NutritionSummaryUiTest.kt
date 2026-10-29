package com.vipin.fitness.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.NutritionAdherence
import com.vipin.fitness.core.data.NutritionSummary
import com.vipin.fitness.core.data.NutritionSummaryPoint
import com.vipin.fitness.core.data.NutritionTargets
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.SummaryBucket
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.feature.nutrition.SummaryTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Nutrition summary (#106) from the diary, through the real MainActivity, with an in-memory repository. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NutritionSummaryUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var workouts: FakeWorkoutRepository
    @Inject lateinit var units: FakeUnitPreference
    @Inject lateinit var summary: FakeAppNutritionSummaryRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    /** One point per period from [from]; the newest is logged, the one before it is empty. */
    private fun build(from: LocalDate, to: LocalDate, b: SummaryBucket): ApiResult<NutritionSummary> {
        val step: (LocalDate) -> LocalDate = when (b) {
            SummaryBucket.DAY -> { d -> d.plusDays(1) }
            SummaryBucket.WEEK -> { d -> d.plusWeeks(1) }
            SummaryBucket.MONTH -> { d -> d.plusMonths(1) }
        }
        val periods = generateSequence(from, step).takeWhile { !it.isAfter(to) }.toList()
        val points = periods.mapIndexed { i, p ->
            val days = if (b == SummaryBucket.DAY) 1 else 7
            if (i == periods.size - 2) NutritionSummaryPoint(p, days, 0, null, null, 0, null, null)
            else NutritionSummaryPoint(
                p, days, 5, Nutrients(1850.4, 92.0, 200.5, 60.0), 10000, 5, 2000,
                NutritionAdherence(92.5, 102.0, null, null, 80.0),
            )
        }
        return ApiResult.Success(NutritionSummary(from, to, b, NutritionTargets(2000, 90, null, null, 2500), points))
    }

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
        workouts.refreshResult = { ApiResult.Success(ActivePlan.None) }
        summary.result = ::build
    }

    @After fun tearDown() { scenario?.close() }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun present(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun until(c: () -> Boolean) = compose.waitUntil(5_000) {
        Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle(); c()
    }
    private fun waitTag(t: String) = until { present(t) }
    /**
     * The fake answers without suspending, so a bucket switch can skip the loading frame and the
     * lazy list keeps its scroll anchor by key; scroll to the card instead of assuming it is on top.
     */
    private fun card(d: LocalDate): Boolean {
        val m = hasTestTag(SummaryTags.point(d))
        if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isEmpty()) return false
        try { compose.onNode(hasScrollAction()).performScrollToNode(m) } catch (_: AssertionError) { return false }
        return present(SummaryTags.point(d))
    }
    /** Scrolls when the node is inside a scrollable; chips and buttons outside one are left as they are. */
    private fun scrollTo(t: String) { try { tag(t).performScrollTo() } catch (_: AssertionError) {} }
    private fun click(t: String) { waitTag(t); tag(t).performClick(); compose.waitForIdle() }

    private fun openSummary() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("tab_NUTRITION"); tag("tab_NUTRITION").performClick(); compose.waitForIdle()
        waitTag("open_nutrition_summary"); scrollTo("open_nutrition_summary"); tag("open_nutrition_summary").performClick(); compose.waitForIdle()
    }
    private fun newest() = summary.calls.last().let { (from, to, b) ->
        (build(from, to, b) as ApiResult.Success).value.points.last().period
    }

    @Test fun opensOnWeeksOfEightMondaysWithAveragesAndAdherence() {
        openSummary()
        until { summary.calls.isNotEmpty() && card(newest()) }
        val (from, to, b) = summary.calls.single()
        assertEquals(SummaryBucket.WEEK, b)
        assertEquals(DayOfWeek.MONDAY, from.dayOfWeek)
        assertEquals(7, ChronoUnit.WEEKS.between(from, to.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))))
        assertTrue(shows("Food logged 5 of 7 days")); assertTrue(shows("Water logged 5 of 7 days"))
        assertTrue(shows("1,850 kcal · P 92 g · C 200.5 g · F 60 g"))
        assertTrue(shows("Water: 2,000 ml per logged day"))
        // Only the targets that exist get a percentage.
        assertTrue(shows("Calories 92.5% · Protein 102% · Water 80%"))
        assertFalse(shows("Carbs ")); assertFalse(shows("Fat "))
        assertTrue(shows("Week of "))
    }

    @Test fun emptyPeriodsShowNothingLoggedNotZeros() {
        openSummary()
        until { summary.calls.isNotEmpty() && card(newest()) }
        val (from, to, b) = summary.calls.last()
        val empty = (build(from, to, b) as ApiResult.Success).value.points.single { it.empty }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(SummaryTags.point(empty.period))); compose.waitForIdle()
        assertTrue(shows("Nothing logged")); assertTrue(shows("Food logged 0 of 7 days"))
        assertFalse(compose.onAllNodes(hasText("0 kcal", substring = true) and hasAnyAncestor(hasTestTag(SummaryTags.point(empty.period))), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun switchingBucketsUsesTheirRanges() {
        openSummary()
        until { summary.calls.size == 1 }
        click(SummaryTags.bucket(SummaryBucket.DAY))
        until { summary.calls.size == 2 && card(newest()) }
        summary.calls[1].let { (from, to, b) -> assertEquals(SummaryBucket.DAY, b); assertEquals(13, ChronoUnit.DAYS.between(from, to)) }
        assertFalse(shows("Week of "))
        click(SummaryTags.bucket(SummaryBucket.MONTH))
        until { summary.calls.size == 3 && card(newest()) }
        summary.calls[2].let { (from, to, b) ->
            assertEquals(SummaryBucket.MONTH, b); assertEquals(1, from.dayOfMonth)
            assertEquals(5, ChronoUnit.MONTHS.between(from, to.withDayOfMonth(1)))
        }
        // Re-selecting the current bucket doesn't reload.
        click(SummaryTags.bucket(SummaryBucket.MONTH))
        assertEquals(3, summary.calls.size)
    }

    @Test fun offlineThenRetry() {
        summary.result = { _, _, _ -> FAKE_NETWORK_FAILURE }
        openSummary()
        waitTag(SummaryTags.ERROR)
        assertTrue(shows("You're offline. Connect to see your summary."))
        summary.result = ::build
        click(SummaryTags.RETRY)
        until { card(newest()) }
        assertFalse(present(SummaryTags.ERROR))
    }

    @Test fun aSlowOlderBucketDoesNotOverwriteTheNewOne() {
        val slow = CompletableDeferred<Unit>()
        summary.gates[SummaryBucket.WEEK] = slow
        openSummary()
        waitTag(SummaryTags.LOADING)
        click(SummaryTags.bucket(SummaryBucket.DAY))
        until { summary.calls.size == 2 && card(newest()) }
        slow.complete(Unit)
        repeat(5) { compose.waitForIdle(); shadowOf(android.os.Looper.getMainLooper()).idle() }
        assertFalse(shows("Week of "))
        assertTrue(card(newest()))
    }

    @Test fun waterFollowsImperialUnits() {
        units.units.value = UnitSystem.IMPERIAL
        openSummary()
        until { summary.calls.isNotEmpty() && card(newest()) }
        assertTrue(shows("Water: 67.6 fl oz per logged day"))
    }

    @Test fun backReturnsToTheDiary() {
        openSummary()
        click(SummaryTags.BACK)
        waitTag("open_nutrition_summary")
        assertFalse(present(SummaryTags.BACK))
    }
}
