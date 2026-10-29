package com.vipin.fitness.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeXpLedgerRepository
import com.vipin.fitness.core.testing.testXpEntry
import com.vipin.fitness.feature.progress.XpLedgerTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** XP ledger through the real MainActivity: Home XP card → ledger, data from FakeXpLedgerModule. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class XpLedgerScreenUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var ledger: FakeXpLedgerRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
    }

    @After fun tearDown() { scenario?.close() }

    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(XpLedgerTags.OPEN), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodes(hasTestTag("home_list")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag(XpLedgerTags.OPEN))
        compose.onNodeWithTag(XpLedgerTags.OPEN).performScrollTo().performClick()
        waitTag(XpLedgerTags.SCREEN)
    }

    @Test fun showsSignedRowsIncludingCorrectionsAndUnknownTypes() {
        ledger.pages[null] = listOf(
            testXpEntry("t2", -40, "adjustment", createdAt = "2026-10-21T06:00:00Z"),
            testXpEntry("c1", -5, "session_correction", "Session corrected"),
            testXpEntry("u1", 7, "something_new", "Mystery"),
            testXpEntry("t1", 40),
        ) to null
        open()
        waitTag(XpLedgerTags.row("t2"))
        compose.onNodeWithTag(XpLedgerTags.amount("t2"), useUnmergedTree = true).assert(hasText("\u221240 XP"))
        compose.onNodeWithTag(XpLedgerTags.BALANCE).assertExists()
        compose.onNodeWithTag(XpLedgerTags.LIST).performScrollToNode(hasTestTag(XpLedgerTags.row("u1")))
        compose.onNodeWithTag(XpLedgerTags.row("u1")).assertExists()
        compose.onNodeWithTag(XpLedgerTags.LIST).performScrollToNode(hasTestTag(XpLedgerTags.row("t1")))
        compose.onNodeWithTag(XpLedgerTags.amount("t1"), useUnmergedTree = true).assert(hasText("+40 XP"))
        assertEquals(listOf<String?>(null), ledger.calls)
    }

    @Test fun pagesWithoutDuplicates() {
        ledger.pages[null] = (1..20).map { testXpEntry("a$it", 10) } to "c2"
        ledger.pages["c2"] = listOf(testXpEntry("a20", 10), testXpEntry("b1", 50, "badge")) to null
        open()
        waitTag(XpLedgerTags.LIST)
        compose.onNodeWithTag(XpLedgerTags.LIST).performScrollToNode(hasTestTag(XpLedgerTags.row("a20")))
        compose.waitUntil(5_000) { ledger.calls.size == 2 }
        compose.onNodeWithTag(XpLedgerTags.LIST).performScrollToNode(hasTestTag(XpLedgerTags.row("b1")))
        assertEquals(1, compose.onAllNodes(hasTestTag(XpLedgerTags.row("a20"))).fetchSemanticsNodes().size)
        assertEquals(listOf(null, "c2"), ledger.calls)
    }

    @Test fun offlineShowsErrorThenRetryLoads() {
        open()
        waitTag(XpLedgerTags.ERROR)
        ledger.pages[null] = listOf(testXpEntry("t1", 40)) to null
        compose.onNodeWithTag(XpLedgerTags.RETRY).performClick()
        waitTag(XpLedgerTags.row("t1"))
    }
}
