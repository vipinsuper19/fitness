package com.vipin.fitness.feature.progress

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.ProgressPoint
import com.vipin.fitness.core.data.MeasurementKind
import com.vipin.fitness.core.testing.FakeProgressRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ChartsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeProgressRepository()
    private val units = FakeUnitPreference()
    private val clock = MutableClock(Instant.parse("2026-10-21T18:20:00Z")) // 23:50 IST, 21 Oct
    private val oct21 = LocalDate.of(2026, 10, 21)

    @Test fun rangeEndsTodayInIndia() {
        val vm = ChartsViewModel(repo, units, clock)
        assertEquals(oct21.minusDays(29) to oct21, repo.summaryCalls.single())
        vm.setRange(ChartRange.WEEK)
        assertEquals(oct21.minusDays(6) to oct21, repo.summaryCalls.last())
    }

    @Test fun resumeAfterMidnightReloadsForTheNewDay() {
        val vm = ChartsViewModel(repo, units, clock)
        vm.onResume()
        assertEquals(1, repo.summaryCalls.size)
        clock.instant = Instant.parse("2026-10-21T18:40:00Z")
        vm.onResume()
        assertEquals(LocalDate.of(2026, 10, 22), repo.summaryCalls.last().second)
    }

    @Test fun missingValuesAreGapsNotZeroAndWeightUsesDisplayUnits() {
        repo.points = mapOf(
            oct21.minusDays(2) to ProgressPoint(oct21.minusDays(2), weightKg = 80.0, workoutsCompleted = 0, activeMinutes = 0, energyKcal = null, xpEarned = 0),
            oct21 to ProgressPoint(oct21, weightKg = null, workoutsCompleted = 1, activeMinutes = 45, energyKcal = 0.0, xpEarned = 50),
        )
        units.units.value = UnitSystem.IMPERIAL
        val vm = ChartsViewModel(repo, units, clock)
        vm.setRange(ChartRange.WEEK)
        val s = vm.state.value
        val weight = s.series(ChartMetric.WEIGHT)
        assertEquals(7, weight.size)
        assertEquals(listOf(null, null, null, null), weight.take(4))
        assertEquals(176.37, weight[4]!!, 0.01)
        assertEquals(null, weight[5]) // day missing from the response: a gap
        assertEquals(null, weight[6]) // not measured
        val energy = s.series(ChartMetric.ENERGY)
        assertEquals(null, energy[4]) // nothing logged
        assertEquals(0.0, energy[6]) // a real zero stays zero
        assertEquals(listOf(0.0, null, 1.0), s.series(ChartMetric.WORKOUTS).takeLast(3))
    }

    @Test fun segmentsBreakOnNullButNotOnZero() {
        assertEquals(
            listOf(listOf(0 to 1.0, 1 to 0.0), listOf(3 to 2.0)),
            chartSegments(listOf(1.0, 0.0, null, 2.0, null)),
        )
        assertEquals(emptyList<Any>(), chartSegments(listOf(null, null)))
    }

    @Test fun formattingRoundsHalfUp() {
        assertEquals("3", ProgressFormat.number(2.5, 0))
        assertEquals("1.5", ProgressFormat.number(1.45))
        assertEquals("1.5", ProgressFormat.input(1.45))
        assertEquals("80 kg", ProgressFormat.measurement(MeasurementKind.WEIGHT, 80.0, UnitSystem.METRIC))
        assertEquals("35.4 in", ProgressFormat.measurement(MeasurementKind.WAIST, 90.0, UnitSystem.IMPERIAL))
    }

    @Test fun waterBodyFatAndWaistSeriesKeepGapsAndConvertWaist() {
        repo.points = mapOf(
            oct21.minusDays(1) to ProgressPoint(oct21.minusDays(1), bodyFatPct = 18.3, waistCm = 90.0, waterMl = 2500),
            oct21 to ProgressPoint(oct21, waterMl = 0),
        )
        val vm = ChartsViewModel(repo, units, clock)
        vm.setRange(ChartRange.WEEK)
        var s = vm.state.value
        assertEquals(listOf(null, 2500.0, 0.0), s.series(ChartMetric.WATER).takeLast(3))
        assertEquals(listOf(null, 18.3, null), s.series(ChartMetric.BODY_FAT).takeLast(3))
        assertEquals(listOf(null, 90.0, null), s.series(ChartMetric.WAIST).takeLast(3))
        units.units.value = UnitSystem.IMPERIAL
        s = vm.state.value
        assertEquals(35.43, s.series(ChartMetric.WAIST)[5]!!, 0.01)
        assertEquals(18.3, s.series(ChartMetric.BODY_FAT)[5]!!, 0.0) // percent never converts
        assertEquals(84.5, s.series(ChartMetric.WATER)[5]!!, 0.1)
    }

    @Test fun chartValuesUseUnitsAndHalfUp() {
        assertEquals("2.5 L", ProgressFormat.chartValue(ChartMetric.WATER, 2500.0, UnitSystem.METRIC))
        assertEquals("1.26 L", ProgressFormat.chartValue(ChartMetric.WATER, 1255.0, UnitSystem.METRIC))
        assertEquals("750 ml", ProgressFormat.chartValue(ChartMetric.WATER, 750.0, UnitSystem.METRIC))
        assertEquals("84.5 fl oz", ProgressFormat.chartValue(ChartMetric.WATER, 84.535, UnitSystem.IMPERIAL))
        assertEquals("18.3 %", ProgressFormat.chartValue(ChartMetric.BODY_FAT, 18.25, UnitSystem.METRIC))
        assertEquals("35.5 in", ProgressFormat.chartValue(ChartMetric.WAIST, 35.45, UnitSystem.IMPERIAL))
        assertEquals("90.1 cm", ProgressFormat.chartValue(ChartMetric.WAIST, 90.05, UnitSystem.METRIC))
    }
}
