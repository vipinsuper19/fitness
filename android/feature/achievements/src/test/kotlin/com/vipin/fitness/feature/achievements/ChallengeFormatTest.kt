package com.vipin.fitness.feature.achievements

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.achievements.ChallengeStatus
import com.vipin.fitness.core.testing.testChallenge
import com.vipin.fitness.core.testing.testHydratedBadge
import com.vipin.fitness.core.testing.testWaterChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ChallengeFormatTest {
    @get:Rule val compose = createComposeRule()
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val now = Instant.parse("2026-10-21T06:00:00Z")

    @Test fun waterGoalAtJoinUsesEnInGroupingInMl() {
        assertEquals("Goal at join: 2,500 ml (for information only)", ChallengeFormat.waterGoalAtJoin(res, testWaterChallenge(waterGoalMl = 2500)))
        assertEquals("Goal at join: 250 ml (for information only)", ChallengeFormat.waterGoalAtJoin(res, testWaterChallenge(waterGoalMl = 250)))
        assertEquals("Goal at join: 10,000 ml (for information only)", ChallengeFormat.waterGoalAtJoin(res, testWaterChallenge(waterGoalMl = 10_000)))
        assertEquals("Goal at join: 1,00,000 ml (for information only)", ChallengeFormat.waterGoalAtJoin(res, testWaterChallenge(waterGoalMl = 100_000)))
    }

    @Test fun noGoalNoLine() {
        assertNull(ChallengeFormat.waterGoalAtJoin(res, testWaterChallenge(waterGoalMl = null)))
        assertNull(ChallengeFormat.waterGoalAtJoin(res, testChallenge("a1")))
    }

    @Test fun waterDetection() {
        assertTrue(testWaterChallenge().isWater)
        assertTrue(testWaterChallenge(waterGoalMl = null).isWater)
        assertFalse(testChallenge("a1").isWater)
    }

    @Test fun ruleStringIsOneLine() {
        val rule = res.getString(R.string.challenge_water_rule)
        assertFalse(rule.contains('\n'))
        assertTrue(rule.contains("2000 ml"))
    }

    @Test fun activeWaterCardShowsGoalAndRuleButNotOnWorkouts() {
        val w = testWaterChallenge("w1", waterGoalMl = 2500)
        val a = testChallenge("a1")
        compose.setContent { AchievementsContent(BadgesState(loading = false), ChallengesState(loading = false, active = listOf(w, a)), now, BadgeActions(), ChallengeActions(), initialTab = 1) }
        compose.onNodeWithTag(ChallengeTags.waterGoal("w1"), useUnmergedTree = true).assertTextEquals("Goal at join: 2,500 ml (for information only)")
        compose.onNodeWithTag(ChallengeTags.waterRule("w1"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(ChallengeTags.waterGoal("a1"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(ChallengeTags.waterRule("a1"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun historyWaterCardShowsTheGoalAtJoin() {
        val w = testWaterChallenge("w3", waterGoalMl = 2500, status = ChallengeStatus.COMPLETED)
        compose.setContent { AchievementsContent(BadgesState(loading = false), ChallengesState(loading = false, history = listOf(w)), now, BadgeActions(), ChallengeActions(), initialTab = 1) }
        compose.onNode(hasTestTag("history_w3")).assertExists()
        compose.onNodeWithTag(ChallengeTags.waterGoal("w3"), useUnmergedTree = true).assertTextEquals("Goal at join: 2,500 ml (for information only)")
    }

    @Test fun historyWaterCardWithoutGoalShowsOnlyTheRule() {
        val w = testWaterChallenge("w2", waterGoalMl = null, status = ChallengeStatus.COMPLETED)
        compose.setContent { AchievementsContent(BadgesState(loading = false), ChallengesState(loading = false, history = listOf(w)), now, BadgeActions(), ChallengeActions(), initialTab = 1) }
        compose.onNode(hasTestTag("history_w2")).assertExists()
        compose.onNodeWithTag(ChallengeTags.waterRule("w2"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ChallengeTags.waterGoal("w2"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun hydratedBadgeShowsItsProgress() {
        compose.setContent { AchievementsContent(BadgesState(loading = false, badges = listOf(testHydratedBadge(current = 3))), ChallengesState(loading = false), now, BadgeActions(), ChallengeActions(), initialTab = 0) }
        compose.onNodeWithTag("badge_HYDRATED_7").assertIsDisplayed()
    }
}
