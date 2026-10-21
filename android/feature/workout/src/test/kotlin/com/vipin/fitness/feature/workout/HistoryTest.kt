package com.vipin.fitness.feature.workout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.history.SessionHistoryPage
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeWorkoutHistoryRepository
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testExercise
import com.vipin.fitness.core.testing.testPastSession
import com.vipin.fitness.core.testing.testPastSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun page(vararg ids: String, next: String? = null) =
    ApiResult.Success(SessionHistoryPage(ids.map { testPastSession(it) }, next))

class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeWorkoutHistoryRepository()

    @Test fun pagesThroughAndDropsDuplicates() {
        repo.pages = { c -> if (c == null) page("a", "b", next = "c2") else page("b", "c") }
        val vm = HistoryViewModel(repo)
        vm.loadMore()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.nextCursor)
        vm.loadMore() // last page: nothing more to ask for
        assertEquals(listOf(null, "c2"), repo.historyCalls)
    }

    @Test fun emptyOfflineAndFailedFirstPage() {
        repo.pages = { page() }
        assertTrue(HistoryViewModel(repo).state.value.empty)
        repo.pages = { ApiResult.Failure(ApiError.Network) }
        assertEquals(HistoryError.OFFLINE, HistoryViewModel(repo).state.value.error)
        repo.pages = { ApiResult.Failure(ApiError.Http(500, "INTERNAL", "x", null)) }
        val vm = HistoryViewModel(repo)
        assertEquals(HistoryError.FAILED, vm.state.value.error)
        repo.pages = { page("a") }
        vm.refresh()
        assertNull(vm.state.value.error)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test fun laterPageFailureKeepsTheListAndCanRetry() {
        var fail = true
        repo.pages = { c -> if (c == null) page("a", next = "c2") else if (fail) ApiResult.Failure(ApiError.Network) else page("b") }
        val vm = HistoryViewModel(repo)
        vm.loadMore()
        assertEquals(HistoryError.OFFLINE, vm.state.value.moreError)
        assertEquals(1, vm.state.value.items.size)
        fail = false
        vm.loadMore()
        assertNull(vm.state.value.moreError)
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.id })
    }
}

class PastSessionViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeWorkoutHistoryRepository()
    private val workouts = FakeWorkoutRepository().apply { catalog["e1"] = testExercise("e1", "Squat") }

    @Test fun loadsSetsAndExerciseNames() {
        val vm = PastSessionViewModel(repo, workouts, "s1")
        assertEquals("s1", vm.state.value.session?.id)
        assertEquals(mapOf("e1" to "Squat"), vm.state.value.exerciseNames)
    }

    @Test fun removedSessionIsNotFoundAndOfflineCanRetry() {
        repo.sessionResult = { ApiResult.Failure(ApiError.Http(404, "NOT_FOUND", "x", null)) }
        assertEquals(HistoryError.NOT_FOUND, PastSessionViewModel(repo, workouts, "gone").state.value.error)
        repo.sessionResult = { ApiResult.Failure(ApiError.Network) }
        val vm = PastSessionViewModel(repo, workouts, "s1")
        assertEquals(HistoryError.OFFLINE, vm.state.value.error)
        repo.sessionResult = { ApiResult.Success(testPastSession(it)) }
        vm.load()
        assertEquals("s1", vm.state.value.session?.id)
    }

    @Test fun setSummaries() {
        assertEquals("10 × 20 kg", testPastSet("e", 0).summary())
        assertEquals("8 reps", testPastSet("e", 0, weightKg = null, reps = 8).summary())
        assertEquals("22.5 kg", testPastSet("e", 0, reps = null, weightKg = 22.5).summary())
        assertEquals("45 s", testPastSet("e", 0, reps = null, weightKg = null).copy(durationSeconds = 45).summary())
        assertEquals("1.5 km", testPastSet("e", 0, reps = null, weightKg = null).copy(distanceM = 1500.0).summary())
    }
}

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listOpensASessionAndAsksForMoreAtTheEnd() {
        var opened: String? = null
        var more = 0
        compose.setContent {
            HistoryContent(HistoryState(loading = false, items = listOf(testPastSession("s1")), nextCursor = "c2"), { opened = it }, {}, { more++ })
        }
        compose.onNodeWithTag(HistoryTags.row("s1")).performClick()
        assertEquals("s1", opened)
        compose.waitForIdle()
        assertEquals(1, more)
    }

    @Test fun emptyAndErrorStates() {
        var state by androidx.compose.runtime.mutableStateOf(HistoryState(loading = false))
        var retries = 0
        compose.setContent { HistoryContent(state, {}, { retries++ }, {}) }
        compose.onNodeWithTag(HistoryTags.EMPTY).assertExists()
        state = HistoryState(loading = false, error = HistoryError.OFFLINE)
        compose.onNodeWithText("offline", substring = true).assertExists()
        compose.onNodeWithTag(HistoryTags.RETRY).performClick()
        assertEquals(1, retries)
    }

    @Test fun detailShowsEverySetWithTheExerciseName() {
        val s = testPastSession("s1", sets = listOf(testPastSet("e1", 0), testPastSet("e1", 1, completed = false), testPastSet("e2", 0)))
        compose.setContent { PastSessionContent(PastSessionState(loading = false, session = s, exerciseNames = mapOf("e1" to "Squat")), {}) }
        compose.onNodeWithText("Squat").assertExists()
        compose.onAllNodesWithTag(HistoryTags.SET).fetchSemanticsNodes().let { assertEquals(3, it.size) }
        compose.onNodeWithText("skipped", substring = true).assertExists()
    }

    @Test fun removedSessionHasNoRetry() {
        compose.setContent { PastSessionContent(PastSessionState(loading = false, error = HistoryError.NOT_FOUND), {}) }
        compose.onNodeWithText("no longer available", substring = true).assertExists()
        compose.onNodeWithTag(HistoryTags.RETRY).assertDoesNotExist()
    }
}
