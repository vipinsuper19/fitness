package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileZone
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionCorrectionRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testBadge
import com.vipin.fitness.core.testing.testPastSession
import com.vipin.fitness.core.testing.testPastSet
import com.vipin.fitness.core.testing.windowClosedFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionCorrectionViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeSessionCorrectionRepository()
    private var profile = Profile()
    // completedAt in testPastSession is 2026-10-20T06:30Z (12:00 IST on the 20th).
    private val session = testPastSession("s1", listOf(testPastSet("e1", 0), testPastSet("e1", 1)))

    private fun vm(now: String = "2026-10-21T10:00:00Z") = SessionCorrectionViewModel(
        repo, ProfileZone { FakeProfileRepository(profile) }, Clock.fixed(Instant.parse(now), ZoneOffset.UTC), "s1",
    ).also { repo.session = session }

    @Test fun windowIsTodayOrYesterdayInTheProfileZone() {
        assertTrue(vm("2026-10-20T20:00:00Z").canCorrect(session)) // 21st 01:30 IST: yesterday
        assertFalse(vm("2026-10-21T19:00:00Z").canCorrect(session)) // 22nd 00:30 IST: too old
        // New York: it's still the 21st there, so the 20th is yesterday.
        profile = Profile(timezone = "America/New_York")
        assertTrue(vm("2026-10-21T19:00:00Z").canCorrect(session))
    }

    @Test fun editShowsXpDeltaAndNewBadgesAndTheReturnedSession() {
        repo.xpDelta = -5
        repo.newBadges = listOf(testBadge("ten", earnedAt = Instant.parse("2026-10-21T10:00:00Z")))
        val vm = vm()
        vm.saveSet(session.sets[0].copy(reps = 8))
        val s = vm.state.value
        assertEquals(CorrectionOutcome(-5, repo.newBadges), s.outcome)
        assertEquals(8, s.session!!.sets.first { it.clientSetId == "cs-e1-0" }.reps)
        assertEquals(listOf("put:cs-e1-0"), repo.calls)
        assertEquals("\u22125 XP", xpDeltaText(-5))
        assertEquals("+5 XP", xpDeltaText(5))
    }

    @Test fun windowClosedFromTheServerHidesEditing() {
        repo.failure = windowClosedFailure()
        val vm = vm()
        assertTrue(vm.canCorrect(session))
        vm.deleteSet("cs-e1-1")
        assertEquals(CorrectionError.WINDOW_CLOSED, vm.state.value.error)
        assertFalse(vm.canCorrect(session))
    }

    @Test fun sessionEmptyAndOfflineMapToTheirMessages() {
        repo.failure = ApiResult.Failure(ApiError.Http(422, "SESSION_EMPTY", "x", null))
        val vm = vm()
        vm.deleteSet("cs-e1-0")
        assertEquals(CorrectionError.SESSION_EMPTY, vm.state.value.error)
        repo.failure = ApiResult.Failure(ApiError.Network)
        vm.deleteSet("cs-e1-0")
        assertEquals(CorrectionError.OFFLINE, vm.state.value.error)
    }

    @Test fun completedAtMustMoveEarlierOnTheSameDay() {
        val vm = vm()
        vm.correctCompletion(session, Instant.parse("2026-10-20T06:40:00Z"), null, null) // later
        assertEquals(CorrectionError.INVALID_TIME, vm.state.value.error)
        assertTrue(repo.calls.isEmpty())
        vm.correctCompletion(session, Instant.parse("2026-10-20T06:20:00Z"), 4, null)
        assertNull(vm.state.value.error)
        assertEquals(Instant.parse("2026-10-20T06:20:00Z"), vm.state.value.session!!.completedAt)
        assertEquals(listOf("completion:2026-10-20T06:20:00Z:4:null"), repo.calls)
    }

    @Test fun discardMarksDiscardedAndAnnouncesIt() {
        val vm = vm()
        vm.discard()
        assertTrue(vm.state.value.discarded)
        assertFalse(vm.canCorrect(session))
        assertEquals(listOf("discard"), repo.calls)
        repo.discardFailure = ApiResult.Failure(ApiError.Network)
        val vm2 = vm()
        vm2.discard()
        assertFalse(vm2.state.value.discarded)
        assertEquals(CorrectionError.OFFLINE, vm2.state.value.error)
    }

    @Test fun typedTimeStaysOnTheSessionsLocalDate() {
        val z = java.time.ZoneId.of("Asia/Kolkata")
        assertEquals(Instant.parse("2026-10-20T06:00:00Z"), completedAtFrom("11:30", Instant.parse("2026-10-20T06:30:00Z"), z))
        assertNull(completedAtFrom("25:00", Instant.parse("2026-10-20T06:30:00Z"), z))
    }
}
