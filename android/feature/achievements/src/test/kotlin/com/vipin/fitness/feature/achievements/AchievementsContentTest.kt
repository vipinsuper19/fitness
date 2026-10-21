package com.vipin.fitness.feature.achievements

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.achievements.ChallengeStatus
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.testing.testBadge
import com.vipin.fitness.core.testing.testChallenge
import com.vipin.fitness.core.testing.testTemplate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AchievementsContentTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-21T06:00:00Z")

    private fun show(b: BadgesState = BadgesState(loading = false), c: ChallengesState = ChallengesState(loading = false), ba: BadgeActions = BadgeActions(), ca: ChallengeActions = ChallengeActions(), tab: Int = 0) =
        compose.setContent { AchievementsContent(b, c, now, ba, ca, initialTab = tab) }

    @Test fun badgesShowEarnedAndLockedWithProgress() {
        show(BadgesState(loading = false, badges = listOf(testBadge("A", Instant.parse("2026-10-20T05:00:00Z")), testBadge("B")), earnedCount = 1))
        compose.onNodeWithText("1 of 2 badges earned").assertIsDisplayed()
        compose.onNodeWithTag("badge_A").assertIsDisplayed()
        compose.onNodeWithText("0 / 1").assertIsDisplayed()
    }

    @Test fun notServedYetShowsComingSoonWithoutRetry() {
        show(BadgesState(loading = false, unavailable = true))
        compose.onNodeWithText("Badges and challenges are coming soon.").assertIsDisplayed()
        compose.onNodeWithTag("achievements_retry").assertDoesNotExist()
        compose.onNodeWithTag("tab_challenges").performClick()
        compose.onNodeWithTag("achievements_unavailable").assertDoesNotExist() // challenges state is fine here
    }

    @Test fun challengesNotServedYetShowsComingSoon() {
        show(c = ChallengesState(loading = false, unavailable = true), tab = 1)
        compose.onNodeWithTag("achievements_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("achievements_retry").assertDoesNotExist()
    }

    @Test fun passedWindowSaysEnded() {
        val c = testChallenge("a1", windowEnd = java.time.LocalDate.of(2026, 10, 18))
        show(c = ChallengesState(loading = false, active = listOf(c)), tab = 1)
        compose.onNodeWithText("Ended").assertIsDisplayed()
    }

    @Test fun badgeErrorOffersRetry() {
        var retried = 0
        show(BadgesState(loading = false, error = ApiError.Network), ba = BadgeActions(refresh = { retried++ }))
        compose.onNodeWithTag("achievements_retry").performClick()
        assertEquals(1, retried)
    }

    @Test fun newlyEarnedBadgeShowsTheSheetOnce() {
        var dismissed = 0
        show(BadgesState(loading = false, badges = listOf(testBadge("A", Instant.EPOCH)), celebrate = listOf(testBadge("A", Instant.EPOCH))), ba = BadgeActions(dismissCelebration = { dismissed++ }))
        compose.onNodeWithTag("badge_earned_sheet").assertIsDisplayed()
        compose.onNodeWithTag("badge_earned_ok").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun joinPicksTargetAndJoins() {
        val t = testTemplate()
        var picked: Pair<String, Int>? = null
        var joined = 0
        show(c = ChallengesState(loading = false, templates = listOf(t)), ca = ChallengeActions(selectTarget = { a, b -> picked = a to b }, join = { joined++ }), tab = 1)
        compose.onNodeWithTag("target_WEEKLY_WORKOUTS_4").performClick()
        assertEquals("WEEKLY_WORKOUTS" to 4, picked)
        compose.onNodeWithTag("join_WEEKLY_WORKOUTS").performClick()
        assertEquals(1, joined)
    }

    @Test fun joinedTemplateCantBeJoinedAgain() {
        show(c = ChallengesState(loading = false, templates = listOf(testTemplate()), active = listOf(testChallenge("a1"))), tab = 1)
        compose.onNodeWithTag("challenges_list").performScrollToNode(hasTestTag("join_WEEKLY_WORKOUTS"))
        compose.onNodeWithTag("join_WEEKLY_WORKOUTS").assertIsNotEnabled()
    }

    @Test fun activeCardShowsProgressAndDaysLeftAndLeaveConfirms() {
        val c = testChallenge("a1")
        var asked = 0
        var confirmed = 0
        show(c = ChallengesState(loading = false, active = listOf(c), confirmLeave = c), ca = ChallengeActions(askLeave = { asked++ }, confirmLeave = { confirmed++ }), tab = 1)
        compose.onNodeWithText("1 / 3 workouts").assertIsDisplayed()
        compose.onNodeWithText("5 days left").assertIsDisplayed() // Wed 21 → Sun 25 Oct, inclusive
        compose.onNodeWithTag("leave_confirm").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun errorMessageAppearsAsSnackbar() {
        var shown = 0
        show(c = ChallengesState(loading = false, message = ChallengeMessage.LIMIT_REACHED), ca = ChallengeActions(messageShown = { shown++ }), tab = 1)
        compose.onNodeWithText("You can have 5 active challenges. Leave one to join another.").assertIsDisplayed()
        assertEquals(0, shown) // cleared only after it has been shown (#52)
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertEquals(1, shown)
    }

    @Test fun unknownStatusInHistoryRendersAsEnded() {
        show(c = ChallengesState(loading = false, history = listOf(testChallenge("h1", status = ChallengeStatus.UNKNOWN))), tab = 1)
        compose.onNodeWithTag("challenges_list").performScrollToNode(hasTestTag("history_h1"))
        compose.onNodeWithText("Ended").assertIsDisplayed()
    }
}
