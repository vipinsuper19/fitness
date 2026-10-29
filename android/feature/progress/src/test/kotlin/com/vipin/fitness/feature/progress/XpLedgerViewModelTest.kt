package com.vipin.fitness.feature.progress

import com.vipin.fitness.core.data.xp.XpLedgerPage
import com.vipin.fitness.core.data.xp.XpSourceType
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
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

    private fun page(vararg ids: String, next: String? = null) =
        ApiResult.Success(XpLedgerPage(ids.map { testXpEntry(it) }, next))

    @Test fun pagesThroughAndDropsDuplicates() {
        repo.pages = { c -> if (c == null) page("a", "b", next = "c2") else page("b", "c") }
        val vm = XpLedgerViewModel(repo)
        vm.loadMore()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.nextCursor)
        vm.loadMore() // no cursor: no call
        assertEquals(listOf(null, "c2"), repo.calls)
    }

    @Test fun emptyAndOfflineAndRetry() {
        assertTrue(XpLedgerViewModel(repo).state.value.empty)
        repo.pages = { FAKE_NETWORK_FAILURE }
        val vm = XpLedgerViewModel(repo)
        assertEquals(XpLedgerError.OFFLINE, vm.state.value.error)
        repo.pages = { page("a") }
        vm.refresh()
        assertEquals(listOf("a"), vm.state.value.items.map { it.id })
    }

    @Test fun laterPageFailureKeepsTheList() {
        repo.pages = { c -> if (c == null) page("a", next = "c2") else FAKE_NETWORK_FAILURE }
        val vm = XpLedgerViewModel(repo)
        vm.loadMore()
        assertEquals(XpLedgerError.OFFLINE, vm.state.value.moreError)
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
        assertEquals("20 oct 2026, 12:00 pm", XpFormat.time(corr)?.lowercase())
    }
}
