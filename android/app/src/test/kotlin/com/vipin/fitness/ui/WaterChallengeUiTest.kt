package com.vipin.fitness.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.achievements.ChallengeStatus
import com.vipin.fitness.core.testing.FakeAchievementsRepository
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.testChallenge
import com.vipin.fitness.core.testing.testTemplate
import com.vipin.fitness.core.testing.testWaterChallenge
import com.vipin.fitness.feature.achievements.ChallengeTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * QA (#129): water challenge goal-at-join and rule line through the real MainActivity and ViewModels
 * (FakeAchievementsRepository). ChallengeFormatTest covers the content composable; these cover the
 * repository -> ViewModel -> screen path, leave -> history, and joining from the template list.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class WaterChallengeUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var repo: FakeAchievementsRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    private val rule = "Each day counts against that day's own water target, or 2000 ml if none is set."

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true
        flags.completed = true
        profiles.profile = Profile(onboardingCompleted = true)
    }
    @After fun tearDown() { scenario?.close() }

    private fun waitTag(tag: String, unmerged: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
    private fun openChallenges() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("home_list")
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_achievements"))
        compose.onNodeWithTag("home_achievements").performClick()
        waitTag("tab_challenges")
        compose.onNodeWithTag("tab_challenges").performClick()
        waitTag("challenges_list")
    }
    private fun scrollTo(tag: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("challenges_list").performScrollToNode(hasTestTag(tag))
    }
    private fun goal(id: String) = compose.onNodeWithTag(ChallengeTags.waterGoal(id), useUnmergedTree = true)
    private fun rule(id: String) = compose.onNodeWithTag(ChallengeTags.waterRule(id), useUnmergedTree = true)

    @Test fun activeWaterCardFromTheServerShowsGoalAtJoinAndTheRule() {
        repo.challengeList += testWaterChallenge("w1", waterGoalMl = 12500)
        repo.challengeList += testChallenge("a1")
        openChallenges()
        scrollTo("active_w1")
        goal("w1").assertTextEquals("Goal at join: 12,500 ml (for information only)")
        rule("w1").assertTextEquals(rule)
        scrollTo("active_a1")
        goal("a1").assertDoesNotExist()
        rule("a1").assertDoesNotExist()
    }

    @Test fun leavingAWaterChallengeKeepsTheGoalAtJoinInHistory() {
        repo.challengeList += testWaterChallenge("w1", waterGoalMl = 2500)
        openChallenges()
        scrollTo("leave_w1")
        compose.onNodeWithTag("leave_w1").performClick()
        waitTag("leave_confirm")
        compose.onNodeWithTag("leave_confirm").performClick()
        compose.waitUntil(5_000) { repo.challengeList.single().status == ChallengeStatus.LEFT }
        waitTag("history_w1")
        scrollTo("history_w1")
        goal("w1").assertTextEquals("Goal at join: 2,500 ml (for information only)")
        rule("w1").assertTextEquals(rule)
    }

    @Test fun joiningWaterWithoutAServerGoalShowsTheRuleButInventsNoGoal() {
        repo.templateList += testTemplate("WEEKLY_WATER_DAYS", targets = listOf(3, 5, 7))
        openChallenges()
        scrollTo("join_WEEKLY_WATER_DAYS")
        compose.onNodeWithTag("join_WEEKLY_WATER_DAYS").performClick()
        compose.waitUntil(5_000) { repo.challengeList.isNotEmpty() }
        val joined = repo.challengeList.single()
        assertEquals("WEEKLY_WATER_DAYS", joined.templateCode)
        assertNull(joined.waterGoalMl)
        waitTag("active_${joined.id}")
        scrollTo("active_${joined.id}")
        waitTag(ChallengeTags.waterRule(joined.id), unmerged = true)
        rule(joined.id).assertTextEquals(rule)
        goal(joined.id).assertDoesNotExist()
    }

    @Test fun completedAndExpiredWaterHistoryCardsKeepTheirOwnGoals() {
        repo.challengeList += testWaterChallenge("h1", waterGoalMl = 3000, status = ChallengeStatus.COMPLETED)
        repo.challengeList += testWaterChallenge("h2", waterGoalMl = 1800, status = ChallengeStatus.EXPIRED)
        openChallenges()
        scrollTo("history_h1")
        goal("h1").assertTextEquals("Goal at join: 3,000 ml (for information only)")
        scrollTo("history_h2")
        goal("h2").assertTextEquals("Goal at join: 1,800 ml (for information only)")
        rule("h2").assertTextEquals(rule)
    }
}
