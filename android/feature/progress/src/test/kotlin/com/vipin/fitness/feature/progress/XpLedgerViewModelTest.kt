package com.vipin.fitness.feature.progress

import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileZone
import com.vipin.fitness.core.data.XpBalance
import com.vipin.fitness.core.testing.FakeProfileRepository
import kotlinx.coroutines.CompletableDeferred
import java.time.ZoneId
import com.vipin.fitness.core.data.xp.XpSourceType
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeHomeRepository
import com.vipin.fitness.core.testing.FakeXpLedgerRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testXpEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class XpLedgerViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeXpLedgerRepository()

    private val home = FakeHomeRepository()
    private var profile = Profile()
    private fun vm() = XpLedgerViewModel(repo, home, ProfileZone { FakeProfileRepository(profile) })
    private fun page(vararg ids: String, next: String? = null) = ids.map { testXpEntry(it) } to next

    @Test fun pagesThroughAndDropsDuplicates() {
        repo.pages[null] = page("a", "b", next = "c2")
        repo.pages["c2"] = page("b", "c")
        val vm = vm()
        vm.loadMore()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.nextCursor)
        vm.loadMore() // no cursor: no call
        assertEquals(listOf(null, "c2"), repo.calls)
    }

    @Test fun emptyAndOfflineAndRetry() {
        repo.pages[null] = page()
        assertTrue(vm().state.value.empty)
        repo.pages.clear() // no page = offline
        val vm = vm()
        assertEquals(XpLedgerError.OFFLINE, vm.state.value.error)
        repo.pages[null] = page("a")
        vm.refresh()
        assertEquals(listOf("a"), vm.state.value.items.map { it.id })
    }

    @Test fun laterPageFailureKeepsTheList() {
        repo.pages[null] = page("a", next = "c2")
        val vm = vm()
        vm.loadMore()
        assertEquals(XpLedgerError.OFFLINE, vm.state.value.moreError)
        assertEquals(listOf("a"), vm.state.value.items.map { it.id })
    }

    @Test fun balanceShowsAndItsFailureDoesNotBlockTheList() {
        repo.pages[null] = page("a")
        home.xp = XpBalance(1250, 4, 50, 300)
        assertEquals(1250, vm().state.value.balance?.total)
        home.xpFailure = FAKE_NETWORK_FAILURE
        val vm = vm()
        assertEquals(null, vm.state.value.balance)
        assertEquals(listOf("a"), vm.state.value.items.map { it.id })
    }

    @Test fun correctionsAndUnknownTypesShowWithSignedAmounts() {
        val corr = testXpEntry("c", amount = -1250, type = "session_correction")
        val unknown = testXpEntry("u", amount = 5, type = "streak_bonus")
        assertEquals(R.string.xp_source_session_correction, corr.sourceType.label())
        assertEquals(XpSourceType.UNKNOWN, unknown.sourceType)
        assertEquals(R.string.xp_source_other, unknown.sourceType.label())
        assertEquals("\u22121,250", XpFormat.amount(corr.amount))
        assertEquals("+5", XpFormat.amount(5))
        assertEquals("+0", XpFormat.amount(0))
        // 06:30Z is 12:00 IST.
        assertEquals("20 oct 2026, 12:00 pm", XpFormat.time(corr, APP_ZONE)?.lowercase())
        assertEquals("20 oct 2026, 7:30 am", XpFormat.time(corr, ZoneId.of("Europe/London"))?.lowercase())
    }

    @Test fun zoneComesFromTheProfile() {
        repo.pages[null] = page("a")
        assertEquals(APP_ZONE, vm().state.value.zone)
        profile = Profile(timezone = "Europe/London")
        assertEquals(ZoneId.of("Europe/London"), vm().state.value.zone)
    }

    @Test fun loadMoreLandingAfterARefreshIsIgnored() {
        repo.pages[null] = page("a", next = "c2")
        repo.pages["c2"] = page("b")
        val gate = CompletableDeferred<Unit>()
        repo.gates["c2"] = gate
        val vm = vm()
        vm.loadMore() // waits on the gate
        assertTrue(vm.state.value.loadingMore)
        repo.pages[null] = page("x")
        vm.refresh()
        gate.complete(Unit) // the old page arrives now
        val s = vm.state.value
        assertEquals(listOf("x"), s.items.map { it.id })
        assertNull(s.nextCursor)
        assertEquals(false, s.loadingMore)
    }
}
