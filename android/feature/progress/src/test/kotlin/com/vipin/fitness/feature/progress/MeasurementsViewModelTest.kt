package com.vipin.fitness.feature.progress

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.BodyMeasurement
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.MeasurementKind
import com.vipin.fitness.core.data.MeasurementKind.ARM
import com.vipin.fitness.core.data.MeasurementKind.WAIST
import com.vipin.fitness.core.data.MeasurementKind.WEIGHT
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeProgressRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MeasurementsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeProgressRepository()
    private val units = FakeUnitPreference()
    private var n = 0
    // 23:50 IST on 21 Oct.
    private val clock = MutableClock(Instant.parse("2026-10-21T18:20:00Z"))
    private val oct21 = LocalDate.of(2026, 10, 21)
    private fun vm() = MeasurementsViewModel(repo, units, clock).also { it.newKey = { "k${++n}" } }
    private fun seed() = BodyMeasurement("m0", LocalDate.of(2026, 10, 20), mapOf(WEIGHT to 80.0, WAIST to 90.0)).also { repo.items += it }

    @Test fun addSendsOnlyFilledFieldsInMetricWithKey() {
        val vm = vm()
        vm.startAdd()
        assertEquals(oct21, vm.state.value.form!!.date)
        vm.onText(WEIGHT, "72.5")
        vm.onText(ARM, "31")
        vm.save()
        assertEquals(mapOf(WEIGHT to 72.5, ARM to 31.0), repo.lastNew!!.values)
        assertEquals(oct21, repo.lastNew!!.date)
        assertEquals(listOf("add" to "k1"), repo.writes)
        assertNull(vm.state.value.form)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test fun addRejectsEmptyAndOutOfRangeWithoutRequest() {
        val vm = vm()
        vm.startAdd()
        vm.save()
        assertEquals(setOf(MeasurementsViewModel.FIELD_NONE), vm.state.value.form!!.errors)
        vm.onText(WEIGHT, "19")
        vm.save()
        assertEquals(setOf(WEIGHT.wire), vm.state.value.form!!.errors)
        assertTrue(repo.writes.isEmpty())
    }

    @Test fun imperialInputIsConvertedToMetricAndDisplayedBack() {
        units.units.value = UnitSystem.IMPERIAL
        val vm = vm()
        vm.startAdd()
        vm.onText(WEIGHT, "176.4")
        vm.onText(WAIST, "35.4")
        vm.save()
        assertEquals(80.01, repo.lastNew!!.values.getValue(WEIGHT).let { com.vipin.fitness.core.data.roundMetric(it) }, 0.0)
        assertEquals(89.92, com.vipin.fitness.core.data.roundMetric(repo.lastNew!!.values.getValue(WAIST)), 0.0)
        vm.startEdit(BodyMeasurement("x", oct21, mapOf(WEIGHT to 80.0)))
        assertEquals("176.4", vm.state.value.form!!.texts[WEIGHT])
    }

    @Test fun editSendsOnlyChangedFieldsAndClearsBlankedOnes() {
        val m = seed()
        val vm = vm()
        vm.startEdit(m)
        vm.onText(WEIGHT, "79")
        vm.onText(WAIST, "")
        vm.save()
        assertEquals(mapOf(WEIGHT to Field.Set(79.0), WAIST to Field.Clear), repo.lastChanges!!.values)
        assertNull(repo.lastChanges!!.date)
    }

    @Test fun emptyEditSendsNoRequest() {
        units.units.value = UnitSystem.IMPERIAL // unit round-trip must not count as a change
        val m = seed()
        val vm = vm()
        vm.startEdit(m)
        vm.onText(WEIGHT, vm.state.value.form!!.texts.getValue(WEIGHT) + " ")
        vm.save()
        assertTrue(repo.writes.isEmpty())
        assertNull(vm.state.value.form)
    }

    @Test fun editCannotClearEveryMeasurement() {
        val m = seed()
        val vm = vm()
        vm.startEdit(m)
        vm.onText(WEIGHT, "")
        vm.onText(WAIST, "")
        vm.save()
        assertEquals(setOf(MeasurementsViewModel.FIELD_NONE), vm.state.value.form!!.errors)
        assertTrue(repo.writes.isEmpty())
    }

    @Test fun editDateOnlySendsDate() {
        val m = seed()
        val vm = vm()
        vm.startEdit(m)
        vm.previousDate()
        vm.save()
        assertEquals(LocalDate.of(2026, 10, 19), repo.lastChanges!!.date)
        assertTrue(repo.lastChanges!!.values.isEmpty())
    }

    @Test fun retryReusesKeyAndDeleteGetsItsOwn() {
        val m = seed()
        val vm = vm()
        vm.startEdit(m)
        vm.onText(WEIGHT, "78")
        repo.failNext = FAKE_NETWORK_FAILURE
        vm.save()
        assertNotNull(vm.state.value.error)
        vm.save()
        assertEquals(listOf("update" to "k1", "update" to "k1"), repo.writes)
        vm.delete("m0")
        assertEquals("delete" to "k2", repo.writes.last())
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test fun dateCannotGoPastTodayAndFollowsMidnightOnResume() {
        val vm = vm()
        vm.startAdd()
        vm.nextDate()
        assertEquals(oct21, vm.state.value.form!!.date)
        clock.instant = Instant.parse("2026-10-21T18:40:00Z") // 00:10 IST on 22 Oct
        vm.onResume()
        assertEquals(LocalDate.of(2026, 10, 22), vm.state.value.today)
        assertEquals(LocalDate.of(2026, 10, 22), vm.state.value.form!!.date)
    }

    @Test fun pagesNewestFirst() {
        repo.pageSize = 2
        repeat(3) { i -> repo.items += BodyMeasurement("m$i", oct21.minusDays(i.toLong()), mapOf(MeasurementKind.WEIGHT to 80.0)) }
        val vm = vm()
        assertEquals(listOf("m0", "m1"), vm.state.value.items.map { it.id })
        vm.loadMore()
        assertEquals(listOf("m0", "m1", "m2"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.nextCursor)
    }

    @Test fun reloadDuringLoadMoreResetsPagingAndLoadMoreStillWorks() {
        repo.pageSize = 2
        repeat(3) { i -> repo.items += BodyMeasurement("m$i", oct21.minusDays(i.toLong()), mapOf(WEIGHT to 80.0)) }
        val vm = vm()
        repo.pageGate = kotlinx.coroutines.CompletableDeferred()
        vm.loadMore()
        assertTrue(vm.state.value.loadingMore)
        vm.load()
        assertEquals(false, vm.state.value.loadingMore)
        assertEquals(listOf("m0", "m1"), vm.state.value.items.map { it.id })
        repo.pageGate = null
        vm.loadMore()
        assertEquals(false, vm.state.value.loadingMore)
        assertEquals(listOf("m0", "m1", "m2"), vm.state.value.items.map { it.id })
    }

    @Test fun metricEditShowsTwoDecimals() {
        val vm = vm()
        vm.startEdit(BodyMeasurement("x", oct21, mapOf(WEIGHT to 80.25, WAIST to 90.0)))
        assertEquals("80.25", vm.state.value.form!!.texts[WEIGHT])
        assertEquals("90", vm.state.value.form!!.texts[WAIST])
        vm.save()
        assertTrue(repo.writes.isEmpty())
    }

    @Test fun bodyFatAndWaistEditShowOneDecimalWeightTwo() {
        val vm = vm()
        vm.startEdit(BodyMeasurement("x", oct21, mapOf(WEIGHT to 80.25, WAIST to 90.25, MeasurementKind.BODY_FAT to 18.25)))
        val t = vm.state.value.form!!.texts
        assertEquals("80.25", t[WEIGHT])
        assertEquals("90.3", t[WAIST])
        assertEquals("18.3", t[MeasurementKind.BODY_FAT])
    }
}
