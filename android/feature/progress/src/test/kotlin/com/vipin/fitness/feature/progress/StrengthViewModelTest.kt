package com.vipin.fitness.feature.progress

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.ExerciseProgress
import com.vipin.fitness.core.data.ExerciseProgressPage
import com.vipin.fitness.core.data.ExerciseProgressPoint
import com.vipin.fitness.core.data.PersonalRecords
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileZone
import com.vipin.fitness.core.data.SummaryBucket
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeStrengthProgressRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testExerciseSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StrengthViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeStrengthProgressRepository()
    private val units = FakeUnitPreference()
    private var profile = Profile(timezone = "Asia/Kolkata")
    // 20:00 UTC on 28 Oct is already 29 Oct in India.
    private val clock = MutableClock(Instant.parse("2026-10-28T20:00:00Z"))
    private fun vm() = StrengthViewModel(repo, units, ProfileZone { FakeProfileRepository(profile) }, clock)
    private fun page(vararg ids: String, next: String? = null) = ExerciseProgressPage(ids.map { testExerciseSummary(it) }, next)

    private fun progress(bucket: SummaryBucket) = ExerciseProgress(
        "a", "Bench", TrackingType.REPS_WEIGHT, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-29"), bucket,
        PersonalRecords(maxWeightKg = 100.0), listOf(ExerciseProgressPoint(LocalDate.parse("2026-10-01"), 0, 0)),
    )

    @Test fun listUsesTheProfileDayAndPagesWithoutDuplicates() {
        repo.pages[null] = page("a", "b", next = "c2")
        repo.pages["c2"] = page("b", "c")
        val vm = vm()
        vm.loadMore()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.exerciseId })
        val (from, to, _) = repo.listCalls.first()
        assertEquals(LocalDate.parse("2026-10-29"), to)
        assertEquals(LocalDate.parse("2026-08-01"), from) // 90 days inclusive
        assertEquals(listOf(null, "c2"), repo.listCalls.map { it.third })
        assertEquals(ZoneId.of("Asia/Kolkata"), vm.state.value.zone)
    }

    @Test fun utcProfileUsesItsOwnToday() {
        profile = Profile(timezone = "UTC")
        repo.pages[null] = page()
        val vm = vm()
        assertEquals(LocalDate.parse("2026-10-28"), repo.listCalls.single().second)
        assertTrue(vm.state.value.empty)
    }

    @Test fun offlineThenRetryAndMoreErrorKeepsTheList() {
        val vm = vm()
        assertEquals(StrengthError.OFFLINE, vm.state.value.error)
        repo.pages[null] = page("a", next = "c2")
        vm.refresh()
        assertNull(vm.state.value.error)
        vm.loadMore()
        assertEquals(StrengthError.OFFLINE, vm.state.value.moreError)
        assertEquals(listOf("a"), vm.state.value.items.map { it.exerciseId })
    }

    @Test fun detailRangesStayWithin366DaysForEveryBucket() {
        repo.pages[null] = page("a")
        repo.detail = { _, _, _, b -> ApiResult.Success(progress(b)) }
        val vm = vm()
        vm.open("a")
        assertEquals(SummaryBucket.WEEK, vm.state.value.detail!!.progress!!.bucket)
        vm.setBucket(SummaryBucket.DAY)
        vm.setBucket(SummaryBucket.MONTH)
        assertEquals(
            listOf("a:2026-05-01:2026-10-29:week", "a:2026-09-30:2026-10-29:day", "a:2025-10-29:2026-10-29:month"),
            repo.detailCalls,
        )
        SummaryBucket.entries.forEach { b ->
            val (f, t) = StrengthRanges.detail(LocalDate.parse("2028-03-01"), b)
            assertTrue(java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1 <= 366)
        }
        vm.closeDetail()
        assertNull(vm.state.value.detail)
    }

    @Test fun detailNotFoundAndOfflineRetry() {
        repo.pages[null] = page("a")
        repo.detail = { _, _, _, _ -> ApiResult.Failure(ApiError.Http(404, "NOT_FOUND", "x", null)) }
        val vm = vm()
        vm.open("gone")
        assertEquals(StrengthError.NOT_FOUND, vm.state.value.detail!!.error)
        repo.detail = { _, _, _, _ -> ApiResult.Failure(ApiError.Network) }
        vm.retryDetail()
        assertEquals(StrengthError.OFFLINE, vm.state.value.detail!!.error)
        repo.detail = { _, _, _, b -> ApiResult.Success(progress(b)) }
        vm.retryDetail()
        assertEquals("Bench", vm.state.value.detail!!.progress!!.name)
    }

    @Test fun unitsFollowThePreference() {
        repo.pages[null] = page("a")
        val vm = vm()
        units.units.value = UnitSystem.IMPERIAL
        assertEquals(UnitSystem.IMPERIAL, vm.state.value.units)
    }
}

class StrengthFormatTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    @Test fun enInGroupingAndHalfUp() {
        assertEquals("1,00,000", StrengthFormat.number(100000.0))
        assertEquals("12,34,567.5", StrengthFormat.number(1234567.45))
        assertEquals("2.5", StrengthFormat.number(2.45))
        assertEquals("3", StrengthFormat.number(2.5, 0))
        assertEquals("999", StrengthFormat.number(999.0))
    }

    @Test fun weightsFollowTheUnitSystem() {
        assertEquals("100 kg", StrengthFormat.weight(100.0, UnitSystem.METRIC))
        assertEquals("220.5 lb", StrengthFormat.weight(100.0, UnitSystem.IMPERIAL))
        assertEquals("1,250 kg", StrengthFormat.volume(1250.25, UnitSystem.METRIC))
        assertEquals("2,756 lb", StrengthFormat.volume(1250.25, UnitSystem.IMPERIAL))
    }

    @Test fun durationAndDistance() {
        assertEquals("45 s", StrengthFormat.duration(45))
        assertEquals("2:05", StrengthFormat.duration(125))
        assertEquals("1:00:05", StrengthFormat.duration(3605))
        assertEquals("800 m", StrengthFormat.distance(800.0, UnitSystem.METRIC))
        assertEquals("5 km", StrengthFormat.distance(5000.0, UnitSystem.METRIC))
        assertEquals("3.11 mi", StrengthFormat.distance(5000.0, UnitSystem.IMPERIAL))
    }

    @Test fun datesUseTheProfileZone() {
        val t = Instant.parse("2026-10-28T19:00:00Z") // 00:30 on 29 Oct in India
        assertEquals("29 Oct 2026", StrengthFormat.date(t, ist))
        assertEquals("28 Oct 2026", StrengthFormat.date(t, ZoneId.of("UTC")))
    }

    @Test fun nullRecordsAndPointValuesAreOmitted() {
        val pr = PersonalRecords(maxDurationSec = 90, maxDurationSecAchievedAt = Instant.parse("2026-10-28T19:00:00Z"))
        assertEquals("1:30", prLine(pr, UnitSystem.METRIC))
        assertEquals(listOf("Longest set: 1:30 (29 Oct 2026)"), prRows(pr, UnitSystem.METRIC, ist))
        val p = ExerciseProgressPoint(LocalDate.parse("2026-10-01"), 1, 3, totalDistanceM = 1500.0, maxSetDistanceM = 600.0)
        assertEquals("3 sets · Distance 1.5 km · Best set 600 m", pointLine(p, UnitSystem.METRIC))
    }
}
