package com.vipin.fitness.feature.achievements

import com.vipin.fitness.core.data.achievements.BadgeList
import com.vipin.fitness.core.data.achievements.ChallengeStatus
import com.vipin.fitness.core.data.achievements.EarnedBadgeTracker
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.testing.FakeAchievementsRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testBadge
import com.vipin.fitness.core.testing.testChallenge
import com.vipin.fitness.core.testing.testTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BadgesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeAchievementsRepository()
    private val tracker = EarnedBadgeTracker()

    @Test fun loadsCatalogAndCelebratesOnlyNewlyEarned() {
        repo.badgeList = BadgeList(listOf(testBadge("A", Instant.EPOCH), testBadge("B")), 1)
        val vm = BadgesViewModel(repo, tracker)
        assertEquals(listOf("A", "B"), vm.state.value.badges.map { it.code })
        assertTrue(vm.state.value.celebrate.isEmpty()) // baseline
        repo.badgeList = BadgeList(listOf(testBadge("A", Instant.EPOCH), testBadge("B", Instant.EPOCH)), 2)
        vm.refresh()
        assertEquals(listOf("B"), vm.state.value.celebrate.map { it.code })
        assertEquals(2, vm.state.value.earnedCount)
        vm.dismissCelebration()
        assertTrue(vm.state.value.celebrate.isEmpty())
    }

    @Test fun notServedYetIsUnavailableNotAnError() {
        repo.failWith = ApiError.Http(404, "NOT_FOUND", "", null)
        val vm = BadgesViewModel(repo, tracker)
        assertTrue(vm.state.value.unavailable)
        assertNull(vm.state.value.error)
        repo.failWith = ApiError.Http(500, "INTERNAL", "", null)
        vm.refresh()
        assertEquals(500, (vm.state.value.error as ApiError.Http).status)
    }

    @Test fun failureKeepsLoadedBadges() {
        repo.badgeList = BadgeList(listOf(testBadge("A")), 0)
        val vm = BadgesViewModel(repo, tracker)
        repo.failWith = ApiError.Network
        vm.refresh()
        assertEquals(ApiError.Network, vm.state.value.error)
        assertEquals(1, vm.state.value.badges.size)
    }
}

class ChallengesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeAchievementsRepository().apply {
        templateList += testTemplate("WEEKLY_WORKOUTS")
        templateList += testTemplate("WEEKLY_WATER_DAYS", targets = listOf(3, 5, 7))
        challengeList += testChallenge("old", "WEEKLY_FOOD_LOG_DAYS", ChallengeStatus.COMPLETED)
        challengeList += testChallenge("odd", "NEW_THING", ChallengeStatus.UNKNOWN)
    }
    private val clock = Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC)
    private fun vm() = ChallengesViewModel(repo, clock)

    @Test fun splitsActiveFromHistoryIncludingUnknownStatus() {
        repo.challengeList += testChallenge("a1")
        val s = vm().state.value
        assertEquals(listOf("a1"), s.active.map { it.id })
        assertEquals(listOf("old", "odd"), s.history.map { it.id })
        assertNull(s.loadError)
    }

    @Test fun joinUsesSelectedTargetAndShowsItAsActive() {
        val vm = vm()
        val t = vm.state.value.templates[1]
        vm.selectTarget(t.code, 5)
        vm.join(t)
        val s = vm.state.value
        assertEquals(5, s.active.single().target)
        assertTrue(s.isJoined(t))
        assertEquals(ChallengeMessage.JOINED, s.message)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test fun offlineJoinRetryReusesTheKeyButANewTapAfterSuccessDoesNot() {
        val vm = vm()
        val t = vm.state.value.templates[0]
        repo.failWith = ApiError.Network
        vm.join(t)
        assertEquals(ChallengeMessage.OFFLINE, vm.state.value.message)
        repo.failWith = null
        vm.join(t)
        assertEquals(1, repo.joinKeys.distinct().size)
        vm.join(vm.state.value.templates[1])
        assertEquals(2, repo.joinKeys.distinct().size)
    }

    @Test fun conflictsMapToMessages() {
        repo.challengeList += (1..5).map { testChallenge("x$it", "T$it") }
        val vm = vm()
        vm.join(vm.state.value.templates[0])
        assertEquals(ChallengeMessage.LIMIT_REACHED, vm.state.value.message)
        assertEquals(ChallengeMessage.ALREADY_JOINED, ApiError.Http(409, "CHALLENGE_ALREADY_JOINED", "", null).toMessage())
        assertEquals(ChallengeMessage.FAILED, ApiError.Http(400, "SOMETHING_NEW", "", null).toMessage())
    }

    @Test fun leaveAsksFirstThenMovesToHistory() {
        repo.challengeList += testChallenge("a1")
        val vm = vm()
        val c = vm.state.value.active.single()
        vm.askLeave(c)
        assertEquals(c, vm.state.value.confirmLeave)
        vm.cancelLeave()
        assertTrue(repo.leaveKeys.isEmpty())
        vm.askLeave(c)
        vm.confirmLeave()
        val s = vm.state.value
        assertTrue(s.active.isEmpty())
        assertEquals(ChallengeStatus.LEFT, s.history.first().status)
        assertEquals(ChallengeMessage.LEFT, s.message)
    }

    @Test fun leavingAnEndedChallengeSaysSo() {
        val vm = vm()
        vm.askLeave(vm.state.value.history.first()) // completed
        vm.confirmLeave()
        assertEquals(ChallengeMessage.NOT_ACTIVE, vm.state.value.message)
    }

    @Test fun notServedYetIsUnavailableNotAnError() {
        repo.failWith = ApiError.Http(404, "NOT_FOUND", "", null)
        val s = vm().state.value
        assertTrue(s.unavailable)
        assertNull(s.loadError)
    }

    @Test fun loadMoreIgnoresADoubleTap() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : com.vipin.fitness.core.data.achievements.AchievementsRepository by repo {
            var calls = 0
            override suspend fun challenges(status: ChallengeStatus?, cursor: String?) =
                if (cursor == null) com.vipin.fitness.core.network.ApiResult.Success(com.vipin.fitness.core.data.achievements.ChallengePage(emptyList(), "c2"))
                else { calls++; gate.await(); com.vipin.fitness.core.network.ApiResult.Success(com.vipin.fitness.core.data.achievements.ChallengePage(emptyList(), null)) }
        }
        val vm = ChallengesViewModel(slow, clock)
        vm.loadMoreHistory()
        vm.loadMoreHistory()
        assertEquals(1, slow.calls)
        gate.complete(Unit)
        assertNull(vm.state.value.historyCursor)
    }

    @Test fun loadFailureIsShown() {
        repo.failWith = ApiError.Network
        assertEquals(ApiError.Network, vm().state.value.loadError)
    }
}
