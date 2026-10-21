package com.vipin.fitness.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.achievements.BadgeList
import com.vipin.fitness.core.data.achievements.BadgeProgress
import com.vipin.fitness.core.data.achievements.ChallengeStatus
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.testing.FakeAchievementsRepository
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.InMemorySeenBadgeStore
import com.vipin.fitness.core.testing.testBadge
import com.vipin.fitness.core.testing.testChallenge
import com.vipin.fitness.core.testing.testTemplate
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import javax.inject.Inject

/**
 * T16c: badges & challenges screens through the real MainActivity → Home card → Achievements,
 * real ViewModels, with the achievements API replaced by FakeAchievementsRepository (no server).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AchievementsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var repo: FakeAchievementsRepository
    @Inject lateinit var seen: InMemorySeenBadgeStore
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true
        flags.completed = true
        profiles.profile = Profile(onboardingCompleted = true)
    }
    @After fun tearDown() { scenario?.close() }

    private fun waitFor(t: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    private fun openAchievements() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        openFromHome()
        waitTag("tab_badges")
    }
    /** The Home card is a lazy item near the bottom: scroll the list to compose it. */
    private fun openFromHome() {
        waitTag("home_list")
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_achievements"))
        compose.onNodeWithTag("home_achievements").performClick()
    }
    private fun openChallenges() {
        openAchievements()
        compose.onNodeWithTag("tab_challenges").performClick()
        waitTag("challenges_list")
    }
    private fun scrollTo(tag: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("challenges_list").performScrollToNode(hasTestTag(tag))
    }
    private fun gridTo(text: String) = compose.onNodeWithTag("badges_grid").performScrollToNode(hasText(text))

    // ---- badges list ----

    @Test fun badgesList_showsEarnedCount_earnedDate_progress_andLocked() {
        repo.badgeList = BadgeList(
            listOf(
                testBadge("FIRST_WORKOUT", earnedAt = Instant.parse("2026-10-20T06:00:00Z")),
                testBadge("TEN_WORKOUTS", progress = BadgeProgress(4, 10)),
                testBadge("STREAK_7", progress = null),
            ),
            earnedCount = 1,
        )
        openAchievements()
        waitTag("badges_grid")
        compose.onNodeWithText("1 of 3 badges earned").assertExists()
        compose.onNodeWithText("Badge FIRST_WORKOUT").assertExists()
        compose.onNodeWithText("Earned Oct 20, 2026").assertExists()
        gridTo("4 / 10")
        gridTo("Not earned yet")
        // First list for this user: nothing to celebrate (badges earned before are not "new").
        assertTrue(compose.onAllNodes(hasTestTag("badge_earned_sheet")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun badgesList_empty() {
        openAchievements()
        waitFor("No badges yet.")
    }

    @Test fun badgesList_loadFailure_showsRetry_andRetryRecovers() {
        repo.failWith = ApiError.Network
        openAchievements()
        waitTag("achievements_retry")
        repo.failWith = null
        repo.badgeList = BadgeList(listOf(testBadge("FIRST_WORKOUT")), 0)
        compose.onNodeWithTag("achievements_retry").performClick()
        waitTag("badge_FIRST_WORKOUT")
    }

    // ---- earned-badge dialog ----

    @Test fun earnedBadgeDialog_showsNewBadgeWithBonus_once() {
        runBlocking { seen.recordEarned("u1", emptyList()) } // user has seen their list before
        repo.badgeList = BadgeList(listOf(testBadge("FIRST_WORKOUT", earnedAt = Instant.parse("2026-10-21T06:00:00Z"))), 1)
        openAchievements()
        waitTag("badge_earned_sheet")
        compose.onNodeWithText("Badge earned!").assertExists()
        compose.onNodeWithText("Badge FIRST_WORKOUT · Bonus XP: +10").assertExists()
        compose.onNodeWithTag("badge_earned_ok").performClick()
        gone("badge_earned_sheet")
        // Re-open: already seen, so no second celebration.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        openFromHome()
        waitTag("badge_FIRST_WORKOUT")
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag("badge_earned_sheet")).fetchSemanticsNodes().isEmpty())
    }

    // ---- join / leave ----

    @Test fun joinChallenge_withChosenTarget_movesItToActive() {
        repo.templateList += testTemplate("WEEKLY_WORKOUTS", targets = listOf(2, 3, 4))
        openChallenges()
        waitFor("No active challenges. Pick one below.")
        scrollTo("target_WEEKLY_WORKOUTS_4")
        compose.onNodeWithTag("target_WEEKLY_WORKOUTS_4").performClick()
        compose.onNodeWithTag("join_WEEKLY_WORKOUTS").performClick()
        compose.waitUntil(5_000) { repo.challengeList.isNotEmpty() }
        compose.waitForIdle()
        val joined = repo.challengeList.single()
        assertEquals(4, joined.target)
        assertEquals(1, repo.joinKeys.size)
        scrollTo("active_${joined.id}")
        compose.onNodeWithText("0 / 4 workouts").assertExists()
        scrollTo("join_WEEKLY_WORKOUTS")
        compose.onNodeWithTag("join_WEEKLY_WORKOUTS").assertIsNotEnabled()
        compose.onNodeWithText("Joined").assertExists()
    }

    @Test fun leaveChallenge_asksFirst_staySkeepsIt_leaveMovesItToHistory() {
        repo.templateList += testTemplate("WEEKLY_WORKOUTS")
        repo.challengeList += testChallenge("c9")
        openChallenges()
        scrollTo("leave_c9")
        compose.onNodeWithTag("leave_c9").performClick()
        waitFor("Leave this challenge?")
        compose.onNodeWithText("Stay").performClick()
        gone("leave_confirm")
        assertTrue(repo.leaveKeys.isEmpty())

        compose.onNodeWithTag("leave_c9").performClick()
        waitTag("leave_confirm")
        compose.onNodeWithTag("leave_confirm").performClick()
        compose.waitUntil(5_000) { repo.challengeList.single().status == ChallengeStatus.LEFT }
        compose.waitForIdle()
        assertEquals(ChallengeStatus.LEFT, repo.challengeList.single().status)
        scrollTo("history_c9")
        compose.onNodeWithText("Left").assertExists()
        assertTrue(compose.onAllNodes(hasTestTag("active_c9")).fetchSemanticsNodes().isEmpty())
        scrollTo("join_WEEKLY_WORKOUTS")
        compose.onNodeWithTag("join_WEEKLY_WORKOUTS").assertIsEnabled()
    }

    // ---- coming soon (404) ----

    @Test fun comingSoon_whenApiReturns404_onBothTabs_withoutRetry() {
        repo.failWith = ApiError.Http(404, "NOT_FOUND", "Not found", null)
        openAchievements()
        waitTag("achievements_unavailable")
        compose.onNodeWithText("Badges and challenges are coming soon.").assertExists()
        assertTrue(compose.onAllNodes(hasTestTag("achievements_retry")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("tab_challenges").performClick()
        waitTag("achievements_unavailable")
        assertTrue(compose.onAllNodes(hasTestTag("achievements_retry")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("challenges_list")).fetchSemanticsNodes().isEmpty())
    }

    // ---- limit reached ----

    @Test fun limitReached_sixthJoinShowsLimitMessage_andNothingIsAdded() {
        (1..5).forEach { i ->
            repo.templateList += testTemplate("T$i")
            repo.challengeList += testChallenge("a$i", templateCode = "T$i")
        }
        repo.templateList += testTemplate("WEEKLY_STEPS")
        openChallenges()
        scrollTo("join_WEEKLY_STEPS")
        compose.onNodeWithTag("join_WEEKLY_STEPS").performClick()
        compose.waitUntil(5_000) { repo.joinKeys.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(5, repo.challengeList.count { it.status == ChallengeStatus.ACTIVE })
        assertTrue(repo.challengeList.none { it.templateCode == "WEEKLY_STEPS" })
        compose.onNodeWithTag("join_WEEKLY_STEPS").assertIsEnabled() // can try again after leaving one
    }

    // ---- one-off messages (snackbar) after join / leave / limit ----

    @Test fun joinShowsConfirmationSnackbar() {
        repo.templateList += testTemplate("WEEKLY_WORKOUTS")
        openChallenges()
        scrollTo("join_WEEKLY_WORKOUTS")
        compose.onNodeWithTag("join_WEEKLY_WORKOUTS").performClick()
        waitFor("Challenge joined.")
    }

    @Test fun leaveShowsConfirmationSnackbar() {
        repo.challengeList += testChallenge("c9")
        openChallenges()
        scrollTo("leave_c9")
        compose.onNodeWithTag("leave_c9").performClick()
        waitTag("leave_confirm")
        compose.onNodeWithTag("leave_confirm").performClick()
        waitFor("You left the challenge.")
    }

    @Test fun limitReachedShowsLimitSnackbar() {
        (1..5).forEach { i ->
            repo.templateList += testTemplate("T$i")
            repo.challengeList += testChallenge("a$i", templateCode = "T$i")
        }
        repo.templateList += testTemplate("WEEKLY_STEPS")
        openChallenges()
        scrollTo("join_WEEKLY_STEPS")
        compose.onNodeWithTag("join_WEEKLY_STEPS").performClick()
        waitFor("You can have 5 active challenges. Leave one to join another.")
    }
}
