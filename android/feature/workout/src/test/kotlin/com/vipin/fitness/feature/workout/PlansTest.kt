package com.vipin.fitness.feature.workout

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.workout.PlanStatus
import com.vipin.fitness.core.data.workout.PlanSummaryPage
import com.vipin.fitness.core.data.workout.toDomain
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.model.WorkoutPlanSummaryDto
import com.vipin.fitness.core.testing.FakePlanManagementRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testPlanSummary
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val archived = ApiResult.Failure(ApiError.Http(409, "PLAN_ARCHIVED", "x", null))

class PlansViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakePlanManagementRepository().apply {
        plans += testPlanSummary("a", PlanStatus.ACTIVE)
        plans += testPlanSummary("b", PlanStatus.INACTIVE)
        plans += testPlanSummary("c", PlanStatus.ARCHIVED)
    }
    private var n = 0
    private fun vm() = PlansViewModel(repo).apply { newKey = { "k${++n}" } }

    @Test fun defaultHidesArchivedAndFiltersAskTheServer() {
        val vm = vm()
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.id })
        vm.setFilter(PlanStatus.ARCHIVED)
        assertEquals(listOf("c"), vm.state.value.items.map { it.id })
        assertEquals(listOf(null to null, PlanStatus.ARCHIVED to null), repo.listCalls)
    }

    @Test fun actionsPerStatus() {
        assertEquals(listOf(PlanAction.PAUSE, PlanAction.RESTART, PlanAction.RENAME, PlanAction.ARCHIVE), PlanStatus.ACTIVE.actions())
        assertEquals(PlanAction.RESUME, PlanStatus.PAUSED.actions().first())
        assertEquals(PlanAction.MAKE_ACTIVE, PlanStatus.INACTIVE.actions().first())
        assertEquals(listOf(PlanAction.UNARCHIVE), PlanStatus.ARCHIVED.actions())
    }

    @Test fun lifecycleWritesUseFreshKeysAndReload() {
        val vm = vm()
        vm.perform("b", PlanAction.MAKE_ACTIVE) // switch: a gets paused
        assertEquals(PlanStatus.PAUSED, vm.state.value.items.first { it.id == "a" }.status)
        assertEquals(PlanStatus.ACTIVE, vm.state.value.items.first { it.id == "b" }.status)
        vm.perform("a", PlanAction.RESUME)
        vm.perform("a", PlanAction.PAUSE)
        vm.perform("b", PlanAction.ARCHIVE)
        assertEquals(listOf("a"), vm.state.value.items.map { it.id })
        vm.setFilter(PlanStatus.ARCHIVED)
        vm.perform("c", PlanAction.UNARCHIVE)
        assertEquals(PlanStatus.INACTIVE, repo.plans.first { it.id == "c" }.status)
        assertTrue(vm.state.value.items.none { it.id == "c" })
        assertEquals(listOf("restart:b:k1", "resume:a:k2", "pause:a:k3", "archive:b:k4", "unarchive:c:k5"), repo.calls)
    }

    @Test fun restartOnTheActivePlanPausesFirstAndResetsTheWeek() {
        val vm = vm()
        assertEquals(2, vm.state.value.items.first { it.id == "a" }.currentWeek)
        vm.perform("a", PlanAction.RESTART)
        val a = vm.state.value.items.first { it.id == "a" }
        assertEquals(PlanStatus.ACTIVE, a.status)
        assertEquals(1, a.currentWeek)
    }

    @Test fun unarchiveUnderArchivedFilterDropsTheRowWithoutReloading() {
        repo.plans[2] = repo.plans[2].copy(pausedAt = FakePlanManagementRepository.PAUSED_AT) // archived while paused
        val vm = vm()
        vm.setFilter(PlanStatus.ARCHIVED)
        val lists = repo.listCalls.size
        vm.perform("c", PlanAction.UNARCHIVE)
        // Under the Archived filter the restored plan leaves the list.
        assertTrue(vm.state.value.items.none { it.id == "c" })
        val c = repo.plans.single { it.id == "c" }
        assertEquals(PlanStatus.PAUSED, c.status)
        assertEquals(FakePlanManagementRepository.PAUSED_AT, c.pausedAt)
        assertEquals(lists, repo.listCalls.size)
        assertNull(vm.state.value.busyId)
    }

    @Test fun unarchiveUnderAllUpdatesTheRowInPlace() {
        repo.pages = { _, _ -> ApiResult.Success(PlanSummaryPage(repo.plans.toList(), null)) } // e.g. a stale All list still showing it
        val vm = vm()
        val before = vm.state.value.items
        val lists = repo.listCalls.size
        vm.perform("c", PlanAction.UNARCHIVE)
        val after = vm.state.value.items
        // Same rows in the same order, no reload; only "c" changed.
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(lists, repo.listCalls.size)
        val c = after.single { it.id == "c" }
        assertEquals(PlanStatus.INACTIVE, c.status)
        assertNull(c.pausedAt)
        assertEquals(before.filter { it.id != "c" }, after.filter { it.id != "c" })
        assertNull(vm.state.value.busyId)
    }

    @Test fun unarchiveUnderAllUpdatesAPausedRowInPlaceWithItsPausedAt() {
        repo.plans[2] = repo.plans[2].copy(pausedAt = FakePlanManagementRepository.PAUSED_AT) // archived while paused
        repo.pages = { _, _ -> ApiResult.Success(PlanSummaryPage(repo.plans.toList(), null)) }
        val vm = vm()
        val before = vm.state.value.items
        val index = before.indexOfFirst { it.id == "c" }
        val lists = repo.listCalls.size
        vm.perform("c", PlanAction.UNARCHIVE)
        val after = vm.state.value.items
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(lists, repo.listCalls.size)
        val c = after[index]
        assertEquals("c", c.id)
        assertEquals(PlanStatus.PAUSED, c.status)
        assertEquals(FakePlanManagementRepository.PAUSED_AT, c.pausedAt)
        assertEquals(before[index].name, c.name)
        assertEquals(before.filter { it.id != "c" }, after.filter { it.id != "c" })
        assertNull(vm.state.value.busyId)
    }

    @Test fun planArchivedShowsTheNoticeAndRefreshClearsIt() {
        val vm = vm()
        repo.failure = archived
        vm.perform("a", PlanAction.PAUSE)
        assertEquals(PlansError.ARCHIVED, vm.state.value.actionError)
        repo.failure = null
        vm.dismissActionError()
        vm.refresh()
        assertNull(vm.state.value.actionError)
    }

    @Test fun renameTrimsValidatesAndMapsErrors() {
        val vm = vm()
        vm.perform("a", PlanAction.RENAME)
        vm.submitRename("   ")
        assertEquals(PlansError.INVALID_NAME, vm.state.value.rename?.error)
        vm.submitRename("x".repeat(81))
        assertEquals(PlansError.INVALID_NAME, vm.state.value.rename?.error)
        assertTrue(repo.calls.isEmpty())
        repo.failure = ApiResult.Failure(ApiError.Http(400, "VALIDATION_FAILED", "x", null))
        vm.submitRename("ok")
        assertEquals(PlansError.INVALID_NAME, vm.state.value.rename?.error)
        repo.failure = null
        vm.submitRename("  Summer block  ")
        assertNull(vm.state.value.rename)
        assertEquals("Summer block", vm.state.value.items.first { it.id == "a" }.name)
    }

    @Test fun renamingAnArchivedPlanClosesTheDialogWithTheNotice() {
        val vm = vm()
        vm.startRename("b")
        repo.failure = archived
        vm.submitRename("New")
        assertNull(vm.state.value.rename)
        assertEquals(PlansError.ARCHIVED, vm.state.value.actionError)
    }

    @Test fun loadMoreLandingAfterARefreshIsIgnored() {
        repo.pages = { _, c -> ApiResult.Success(PlanSummaryPage(listOf(testPlanSummary(if (c == null) "p1" else "p2")), if (c == null) "c2" else null)) }
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        repo.listGate = gate
        vm.loadMore()
        repo.listGate = null
        vm.refresh()
        gate.complete(Unit)
        assertEquals(listOf("p1"), vm.state.value.items.map { it.id })
    }

    @Test fun unknownStatusReadsAsInactive() {
        val dto = WorkoutPlanSummaryDto(id = "x", name = "n", source = "custom", version = 1, status = "frozen", totalWeeks = 4, createdAt = "t")
        assertEquals(PlanStatus.INACTIVE, dto.toDomain().status)
    }
}

@RunWith(AndroidJUnit4::class)
class PlansUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun archivedNoticeHasRefresh() {
        var refreshed = 0
        compose.setContent {
            PlansContent(
                PlansState(loading = false, items = listOf(testPlanSummary("a", PlanStatus.PAUSED)), actionError = PlansError.ARCHIVED),
                onFilter = {}, onRefresh = { refreshed++ }, onLoadMore = {}, onAction = { _, _ -> },
                onDismissError = {}, onRename = {}, onCancelRename = {}, onNewPlan = {},
            )
        }
        compose.onNodeWithText("This plan was archived").assertExists()
        compose.onNodeWithTag(PlansTags.REFRESH).performClick()
        assertEquals(1, refreshed)
    }

    @Test fun rowShowsStatusActions() {
        val clicks = mutableListOf<PlanAction>()
        compose.setContent {
            PlansContent(
                PlansState(loading = false, items = listOf(testPlanSummary("a", PlanStatus.PAUSED))),
                onFilter = {}, onRefresh = {}, onLoadMore = {}, onAction = { _, a -> clicks += a },
                onDismissError = {}, onRename = {}, onCancelRename = {}, onNewPlan = {},
            )
        }
        compose.onNodeWithTag(PlansTags.action("a", PlanAction.RESUME)).performClick()
        compose.onNodeWithTag(PlansTags.action("a", PlanAction.RESTART)).performClick()
        assertEquals(listOf(PlanAction.RESUME, PlanAction.RESTART), clicks)
    }
}
