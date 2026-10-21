package com.vipin.fitness.feature.workout

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.achievements.BadgeList
import com.vipin.fitness.core.data.achievements.EarnedBadgeTracker
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.testing.FakeAchievementsRepository
import com.vipin.fitness.core.testing.InMemorySeenBadgeStore
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testBadge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

class SessionBadgesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeAchievementsRepository()
    private val tracker = EarnedBadgeTracker(InMemorySeenBadgeStore())

    private suspend fun baseline() = tracker.newlyEarned(BadgeList(emptyList(), 0))

    @Test fun newlyEarnedShowsOnceAndIsMarkedSeen() = runTest {
        baseline()
        repo.badgeList = BadgeList(listOf(testBadge("FIRST_WORKOUT", Instant.EPOCH), testBadge("STREAK_3")), 1)
        val vm = SessionBadgesViewModel(repo, tracker)
        vm.check()
        assertEquals(listOf("FIRST_WORKOUT"), (vm.state.value as SessionBadgesState.Earned).badges.map { it.code })
        val next = SessionBadgesViewModel(repo, tracker)
        next.check()
        assertEquals(SessionBadgesState.None, next.state.value) // already seen
    }

    @Test fun notServedYetIsComingSoon() {
        repo.failWith = ApiError.Http(404, "NOT_FOUND", "", null)
        val vm = SessionBadgesViewModel(repo, tracker)
        vm.check()
        assertEquals(SessionBadgesState.Unavailable, vm.state.value)
    }

    @Test fun otherFailuresShowNothingAndCanBeRetried() = runTest {
        baseline()
        repo.failWith = ApiError.Network
        val vm = SessionBadgesViewModel(repo, tracker)
        vm.check()
        assertEquals(SessionBadgesState.None, vm.state.value)
        repo.failWith = null
        repo.badgeList = BadgeList(listOf(testBadge("A", Instant.EPOCH)), 1)
        vm.check()
        assertEquals(listOf("A"), (vm.state.value as SessionBadgesState.Earned).badges.map { it.code })
    }
}

@RunWith(AndroidJUnit4::class)
class SessionBadgesContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun earnedBadgesAreListed() {
        compose.setContent { SessionBadgesContent(SessionBadgesState.Earned(listOf(testBadge("FIRST_WORKOUT", Instant.EPOCH)))) }
        compose.onNodeWithText("Badge earned!").assertIsDisplayed()
        compose.onNodeWithTag("summary_badge_FIRST_WORKOUT").assertIsDisplayed()
        compose.onNodeWithText("Bonus XP: +10").assertIsDisplayed()
    }

    @Test fun notServedYetSaysComingSoon() {
        compose.setContent { SessionBadgesContent(SessionBadgesState.Unavailable) }
        compose.onNodeWithText("Badges and challenges are coming soon.").assertIsDisplayed()
    }

    @Test fun failureShowsNothing() {
        compose.setContent { SessionBadgesContent(SessionBadgesState.None) }
        compose.onNodeWithTag("summary_badges_earned").assertDoesNotExist()
        compose.onNodeWithTag("summary_badges_unavailable").assertDoesNotExist()
    }
}
