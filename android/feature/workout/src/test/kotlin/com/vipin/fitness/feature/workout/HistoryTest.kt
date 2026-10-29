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
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun display(profile: Profile = Profile(), units: UnitSystem = UnitSystem.METRIC) =
    HistoryDisplaySource(FakeProfileRepository(profile), FakeUnitPreference(units))

private val stamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun page(vararg ids: String, next: String? = null) =
    ApiResult.Success(SessionHistoryPage(ids.map { testPastSession(it) }, next))

class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeWorkoutHistoryRepository()

    @Test fun pagesThroughAndDropsDuplicates() {
        repo.pages = { c -> if (c == null) page("a", "b", next = "c2") else page("b", "c") }
        val vm = HistoryViewModel(repo, display())
        vm.loadMore()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.nextCursor)
        vm.loadMore() // last page: nothing more to ask for
        assertEquals(listOf(null, "c2"), repo.historyCalls)
    }

    @Test fun emptyOfflineAndFailedFirstPage() {
        repo.pages = { page() }
        assertTrue(HistoryViewModel(repo, display()).state.value.empty)
        repo.pages = { ApiResult.Failure(ApiError.Network) }
        assertEquals(HistoryError.OFFLINE, HistoryViewModel(repo, display()).state.value.error)
        repo.pages = { ApiResult.Failure(ApiError.Http(500, "INTERNAL", "x", null)) }
        val vm = HistoryViewModel(repo, display())
        assertEquals(HistoryError.FAILED, vm.state.value.error)
        repo.pages = { page("a") }
        vm.refresh()
        assertNull(vm.state.value.error)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test fun laterPageFailureKeepsTheListAndCanRetry() {
        var fail = true
        repo.pages = { c -> if (c == null) page("a", next = "c2") else if (fail) ApiResult.Failure(ApiError.Network) else page("b") }
        val vm = HistoryViewModel(repo, display())
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
        val vm = PastSessionViewModel(repo, workouts, display(), "s1")
        assertEquals("s1", vm.state.value.session?.id)
        assertEquals(mapOf("e1" to "Squat"), vm.state.value.exerciseNames)
    }

    @Test fun removedSessionIsNotFoundAndOfflineCanRetry() {
        repo.sessionResult = { ApiResult.Failure(ApiError.Http(404, "NOT_FOUND", "x", null)) }
        assertEquals(HistoryError.NOT_FOUND, PastSessionViewModel(repo, workouts, display(), "gone").state.value.error)
        repo.sessionResult = { ApiResult.Failure(ApiError.Network) }
        val vm = PastSessionViewModel(repo, workouts, display(), "s1")
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

    @Test fun imperialShowsPounds() {
        // 20 kg = 44.092 lb; 22.5 kg = 49.604 lb.
        assertEquals("10 × 44.1 lb", testPastSet("e", 0).summary(UnitSystem.IMPERIAL))
        assertEquals("49.6 lb", testPastSet("e", 0, reps = null, weightKg = 22.5).summary(UnitSystem.IMPERIAL))
    }

    @Test fun numbersAreHalfUpEnInWhateverTheDeviceLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            // "%.1f" would give "22,3" (comma, and binary half-even); en-IN half-up on the decimal value gives 22.3/0.3/2.5.
            assertEquals("22.3 kg", testPastSet("e", 0, reps = null, weightKg = 22.25).summary())
            assertEquals("0.3 kg", testPastSet("e", 0, reps = null, weightKg = 0.25).summary())
            assertEquals("2.5 km", testPastSet("e", 0, reps = null, weightKg = null).copy(distanceM = 2450.0).summary())
            assertEquals("12.5", trimNumber(12.45))
            assertEquals("20", trimNumber(20.0))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun timesUseTheProfileZoneElseAppZone() {
        val s = testPastSession("s1") // completed 2026-10-20T06:30Z
        assertEquals(stamp.format(ZonedDateTime.of(2026, 10, 20, 2, 30, 0, 0, ZoneId.of("America/New_York"))), s.whenText(ZoneId.of("America/New_York")))
        assertEquals(ZoneId.of("America/New_York"), Profile(timezone = "America/New_York").zoneOrDefault())
        assertEquals(APP_ZONE, Profile(timezone = null).zoneOrDefault())
        assertEquals(APP_ZONE, Profile(timezone = "Mars/Olympus").zoneOrDefault())
    }

    @Test fun viewModelsExposeProfileZoneAndUnits() {
        val vm = HistoryViewModel(repo, display(Profile(timezone = "Europe/London"), UnitSystem.IMPERIAL))
        assertEquals(HistoryDisplay(ZoneId.of("Europe/London"), UnitSystem.IMPERIAL), vm.display.value)
        val failing = FakeProfileRepository().apply { getResult = { ApiResult.Failure(ApiError.Network) } }
        val vm2 = HistoryViewModel(repo, HistoryDisplaySource(failing, FakeUnitPreference()))
        assertEquals(HistoryDisplay(APP_ZONE, UnitSystem.METRIC), vm2.display.value)
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
