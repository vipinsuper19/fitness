package com.vipin.fitness.feature.nutrition

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.NutritionAdherence
import com.vipin.fitness.core.data.NutritionSummary
import com.vipin.fitness.core.data.NutritionSummaryPoint
import com.vipin.fitness.core.data.NutritionSummaryRepository
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileZone
import com.vipin.fitness.core.data.SummaryBucket
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

private fun point(d: String, daysLogged: Int = 0, water: Int = 0, adherence: NutritionAdherence? = null) = NutritionSummaryPoint(
    period = LocalDate.parse(d), daysInPeriod = 7, daysLogged = daysLogged,
    avgPerLoggedDay = if (daysLogged > 0) Nutrients(1850.0, 92.45, 210.0, 60.05) else null,
    waterMl = if (water > 0) water * 2000 else null, waterDaysLogged = water,
    avgWaterMlPerLoggedDay = if (water > 0) 2000 else null, adherence = adherence,
)

class FakeNutritionSummaryRepository : NutritionSummaryRepository {
    val calls = mutableListOf<Triple<LocalDate, LocalDate, SummaryBucket>>()
    var result: (SummaryBucket) -> ApiResult<NutritionSummary> = { b ->
        ApiResult.Success(NutritionSummary(LocalDate.MIN, LocalDate.MAX, b, null, listOf(point("2026-10-19"), point("2026-10-26", 3, 2))))
    }
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun summary(from: LocalDate, to: LocalDate, bucket: SummaryBucket): ApiResult<NutritionSummary> {
        calls += Triple(from, to, bucket)
        gate?.await()
        return result(bucket)
    }
}

class NutritionSummaryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeNutritionSummaryRepository()
    // 2026-10-28T20:00Z is already Thursday 29 Oct in Kolkata.
    private val clock = MutableClock(Instant.parse("2026-10-28T20:00:00Z"))
    private val zone = ProfileZone { FakeProfileRepository(Profile(timezone = "Asia/Kolkata")) }
    private fun vm(units: UnitSystem = UnitSystem.METRIC) = NutritionSummaryViewModel(repo, zone, clock, FakeUnitPreference(units))

    @Test fun rangesPerBucketEndTodayInTheProfileZone() {
        val vm = vm()
        vm.select(SummaryBucket.MONTH)
        vm.select(SummaryBucket.DAY)
        val today = LocalDate.parse("2026-10-29")
        assertEquals(
            listOf(
                Triple(LocalDate.parse("2026-09-07"), today, SummaryBucket.WEEK), // Monday, 8 weeks
                Triple(LocalDate.parse("2026-05-01"), today, SummaryBucket.MONTH),
                Triple(LocalDate.parse("2026-10-16"), today, SummaryBucket.DAY),
            ),
            repo.calls,
        )
    }

    @Test fun emptyPeriodsAreKept() {
        val s = vm().state.value
        assertEquals(2, s.summary!!.points.size)
        assertEquals(true, s.summary!!.points[0].empty)
    }

    @Test fun offlineAndFailedThenRetry() {
        repo.result = { ApiResult.Failure(ApiError.Network) }
        val vm = vm()
        assertEquals(SummaryError.OFFLINE, vm.state.value.error)
        repo.result = { ApiResult.Failure(ApiError.Http(400, "VALIDATION_FAILED", "x", null)) }
        vm.load()
        assertEquals(SummaryError.FAILED, vm.state.value.error)
        repo.result = FakeNutritionSummaryRepository().result
        vm.load()
        assertNull(vm.state.value.error)
        assertEquals(2, vm.state.value.summary!!.points.size)
    }

    @Test fun aSlowOlderBucketDoesNotOverwriteTheNewOne() {
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val vm = vm()
        repo.gate = null
        vm.select(SummaryBucket.MONTH)
        gate.complete(Unit)
        assertEquals(SummaryBucket.MONTH, vm.state.value.summary!!.bucket)
    }

    @Test fun unitsFollowThePreference() {
        assertEquals(UnitSystem.IMPERIAL, vm(UnitSystem.IMPERIAL).state.value.units)
    }
}

class NutritionSummaryFormatTest {
    @Test fun waterInMlOrFlOz() {
        assertEquals("2,000 ml", formatWater(2000, UnitSystem.METRIC))
        assertEquals("12,345 ml", formatWater(12_345, UnitSystem.METRIC))
        assertEquals("67.6 fl oz", formatWater(2000, UnitSystem.IMPERIAL))
    }

    @Test fun pctsHalfUp() {
        assertEquals("92.5%", formatPct(92.45))
        assertEquals("100%", formatPct(100.0))
        assertEquals("1,234.6%", formatPct(1234.55))
    }
}

@RunWith(AndroidJUnit4::class)
class NutritionSummaryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsDaysLoggedAveragesAndOnlyTheMacroPctsThatHaveTargets() {
        val adherence = NutritionAdherence(kcalPct = 92.5, proteinPct = 115.6, carbsPct = null, fatPct = null, waterPct = 80.0)
        val s = SummaryState(
            loading = false,
            summary = NutritionSummary(LocalDate.MIN, LocalDate.MAX, SummaryBucket.WEEK, null, listOf(point("2026-10-19"), point("2026-10-26", 3, 2, adherence))),
            units = UnitSystem.IMPERIAL,
        )
        compose.setContent { NutritionSummaryContent(s, {}, {}, {}) }
        compose.onNodeWithText("Food logged 3 of 7 days").assertExists()
        compose.onNodeWithText("Water logged 2 of 7 days").assertExists()
        compose.onNodeWithText("1,850 kcal · P 92.5 g · C 210 g · F 60.1 g").assertExists()
        compose.onNodeWithText("Water: 67.6 fl oz per logged day").assertExists()
        compose.onNodeWithText("Calories 92.5% · Protein 115.6% · Water 80%").assertExists()
        compose.onNodeWithText("Nothing logged").assertExists() // the empty week is still shown
    }

    @Test fun errorHasRetry() {
        var retried = 0
        compose.setContent { NutritionSummaryContent(SummaryState(loading = false, error = SummaryError.OFFLINE), {}, { retried++ }, {}) }
        compose.onNodeWithText("You're offline. Connect to see your summary.").assertExists()
        compose.onNodeWithTag(SummaryTags.RETRY).performClick()
        assertEquals(1, retried)
    }
}
