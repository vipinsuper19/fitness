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
 * QA test-first: XP ledger screen (not on main yet, see ProposedXpGoalsContract.kt). Real
 * MainActivity + navigation; data must come from a Hilt fake, never the network.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class XpLedgerUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    private val ledger = PendingXpLedgerSeed() // TODO: @Inject the real FakeXpLedgerRepository
    private val ui by lazy { UiHelpers(compose) }
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
    }
    @After fun tearDown() { scenario?.close() }

    private fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.waitTag("slot_xp")
        ui.tag(XpLedgerTags.OPEN).performScrollTo().performClick(); compose.waitForIdle()
        ui.waitTag(XpLedgerTags.SCREEN)
    }
    private fun row(id: String, type: String, amount: Int, reason: String = "r", at: String = "2026-10-20T06:00:00Z") = LedgerRow(id, type, amount, reason, at)

    @Ignore("Pending XP ledger screen (not on main)")
    @Test fun empty_showsEmptyState_andCallsFirstPageOnce() {
        ledger.pages[null] = emptyList<LedgerRow>() to null
        open(); ui.waitTag(XpLedgerTags.EMPTY)
        assertEquals(listOf<String?>(null), ledger.calls)
    }

    @Ignore("Pending XP ledger screen (not on main)")
    @Test fun rows_newestFirst_withSignedAmounts() {
        ledger.pages[null] = listOf(row("t2", "adjustment", -40, at = "2026-10-21T06:00:00Z"), row("t1", "workout_session", 40)) to null
        open(); ui.waitTag(XpLedgerTags.row("t2"))
        assertTrue(ui.present(XpLedgerTags.row("t1")))
        assertTrue("reversal shown as negative", ui.shows("−40") || ui.shows("-40"))
        assertTrue(ui.shows("+40"))
    }

    @Ignore("Pending XP ledger screen (not on main)")
    @Test fun unknownAndNewSourceTypes_stillShow() {
        // session-correction.md: clients must accept unknown source types.
        ledger.pages[null] = listOf(row("c1", "session_correction", -5, "Session corrected"), row("u1", "something_new", 7, "Mystery")) to null
        open(); ui.waitTag(XpLedgerTags.row("c1"))
        assertTrue(ui.present(XpLedgerTags.row("u1")))
        assertTrue(ui.shows("Session corrected"))
    }

    @Ignore("Pending XP ledger screen (not on main)")
    @Test fun scrollingToEnd_loadsNextPage_withoutDuplicates() {
        val first = (1..20).map { row("a$it", "workout_session", 10) }
        ledger.pages[null] = first to "c2"
        ledger.pages["c2"] = listOf(row("a20", "workout_session", 10), row("b1", "badge", 50)) to null
        open(); ui.waitTag(XpLedgerTags.LIST)
        ui.tag(XpLedgerTags.LIST).performScrollToNode(hasTestTag(XpLedgerTags.row("a20")))
        ui.until { ledger.calls.size == 2 }
        ui.tag(XpLedgerTags.LIST).performScrollToNode(hasTestTag(XpLedgerTags.row("b1")))
        assertEquals(1, compose.onAllNodes(hasTestTag(XpLedgerTags.row("a20"))).fetchSemanticsNodes().size)
        assertEquals(listOf(null, "c2"), ledger.calls)
    }

    @Ignore("Pending XP ledger screen (not on main)")
    @Test fun offline_showsError_thenRetryLoads() {
        open(); ui.waitTag(XpLedgerTags.ERROR)
        ledger.pages[null] = listOf(row("t1", "workout_session", 40)) to null
        ui.tag(XpLedgerTags.RETRY).performClick()
        ui.waitTag(XpLedgerTags.row("t1"))
    }

    @Ignore("Pending XP ledger screen (not on main)")
    @Test fun readOnly_noEditOrDeleteActions() {
        ledger.pages[null] = listOf(row("t1", "workout_session", 40)) to null
        open(); ui.waitTag(XpLedgerTags.row("t1"))
        assertFalse(ui.shows("Delete")); assertFalse(ui.shows("Edit"))
    }
}
