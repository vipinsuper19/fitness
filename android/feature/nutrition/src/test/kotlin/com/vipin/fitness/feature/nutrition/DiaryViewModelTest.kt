package com.vipin.fitness.feature.nutrition

import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DiaryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeNutritionRepository()
    private var n = 0

    // 20:00 UTC on 20 Oct is 01:30 on 21 Oct in Asia/Kolkata: the diary must use the Indian date.
    private val clock = Clock.fixed(Instant.parse("2026-10-20T20:00:00Z"), ZoneOffset.UTC)
    private fun vm() = DiaryViewModel(repo, clock).also { it.newKey = { "k${++n}" } }

    @Test fun loadsTodayInIndiaTimezone() {
        val vm = vm()
        assertEquals(LocalDate.of(2026, 10, 21), vm.state.value.date)
        assertEquals(listOf(LocalDate.of(2026, 10, 21)), repo.dayCalls)
        assertFalse(vm.state.value.loading)
        assertEquals(1, vm.state.value.logsFor(MealType.BREAKFAST).size)
        assertTrue(vm.state.value.logsFor(MealType.DINNER).isEmpty())
    }

    @Test fun cannotGoPastTodayButCanGoBack() {
        val vm = vm()
        vm.nextDay()
        assertEquals(LocalDate.of(2026, 10, 21), vm.state.value.date)
        vm.previousDay()
        assertEquals(LocalDate.of(2026, 10, 20), vm.state.value.date)
        assertTrue(vm.state.value.canGoForward)
        vm.goToToday()
        assertEquals(LocalDate.of(2026, 10, 21), repo.dayCalls.last())
    }

    @Test fun loadFailureShowsErrorAndRetryRecovers() {
        repo.dayFailure = NETWORK_FAILURE
        val vm = vm()
        assertNotNull(vm.state.value.loadError)
        repo.dayFailure = null
        vm.load()
        assertNull(vm.state.value.loadError)
        assertNotNull(vm.state.value.day)
    }

    @Test fun addWaterSendsKeyAndReloadsTotals() {
        val vm = vm()
        vm.addWater(250)
        assertEquals("addWater" to "k1", repo.writes.single())
        assertEquals(250, vm.state.value.day!!.daily.waterMl)
        vm.addWater(0)
        vm.addWater(6000)
        assertEquals(1, repo.writes.size)
    }

    @Test fun retryOfFailedWriteReusesKeyAndNewActionGetsNewKey() {
        val vm = vm()
        repo.failNext = NETWORK_FAILURE
        vm.addWater(250)
        assertNotNull(vm.state.value.error)
        vm.addWater(250)
        assertEquals(listOf("k1", "k1"), repo.writes.map { it.second })
        vm.addWater(250)
        assertEquals("k2", repo.writes.last().second)
    }

    @Test fun editServingsAndMealSendsOnlyChanges() {
        val vm = vm()
        vm.startEdit(repo.logs.first())
        vm.onEdit { it.copy(servings = "2", meal = MealType.LUNCH) }
        vm.saveEdit()
        assertEquals(MealType.LUNCH, repo.lastChanges!!.mealType)
        assertEquals(2.0, repo.lastChanges!!.servings)
        assertEquals(Field.Unchanged, repo.lastChanges!!.nutrients)
        assertNull(vm.state.value.editing)
    }

    @Test fun editMacrosSendsOverrideAndValidates() {
        val vm = vm()
        vm.startEdit(repo.logs.first())
        vm.onEdit { it.copy(editMacros = true, macros = MacroForm("abc", "1", "2", "3")) }
        vm.saveEdit()
        assertEquals(setOf(DiaryViewModel.FIELD_MACROS), vm.state.value.editing!!.errors)
        assertTrue(repo.writes.isEmpty())
        vm.onEdit { it.copy(macros = MacroForm("150", "5", "25", "3")) }
        vm.saveEdit()
        assertEquals(Field.Set(Nutrients(150.0, 5.0, 25.0, 3.0)), repo.lastChanges!!.nutrients)
    }

    @Test fun restoreComputedSendsClear() {
        repo.logs = mutableListOf(log(edited = true))
        val vm = vm()
        vm.startEdit(repo.logs.first())
        vm.onEdit { it.copy(editMacros = false, restoreComputed = true) }
        vm.saveEdit()
        assertEquals(Field.Clear, repo.lastChanges!!.nutrients)
    }

    @Test fun invalidServingsAreRejectedAndNoChangeClosesWithoutWrite() {
        val vm = vm()
        vm.startEdit(repo.logs.first())
        vm.onEdit { it.copy(servings = "0") }
        vm.saveEdit()
        assertEquals(setOf(DiaryViewModel.FIELD_SERVINGS), vm.state.value.editing!!.errors)
        vm.onEdit { it.copy(servings = "101") }
        vm.saveEdit()
        assertNotNull(vm.state.value.editing)
        vm.onEdit { it.copy(servings = "1") }
        vm.saveEdit()
        assertNull(vm.state.value.editing)
        assertTrue(repo.writes.isEmpty())
    }

    @Test fun deleteLogAndWaterReload() {
        repo.water = mutableListOf(com.vipin.fitness.core.data.WaterLog("w1", LocalDate.of(2026, 10, 21), 300))
        val vm = vm()
        vm.deleteLog("l1")
        vm.updateWater("w1", 400)
        assertEquals(400, vm.state.value.day!!.daily.waterMl)
        vm.deleteWater("w1")
        assertTrue(vm.state.value.day!!.foodLogs.isEmpty())
        assertEquals(0, vm.state.value.day!!.daily.waterMl)
        assertEquals(listOf("deleteFoodLog", "updateWater", "deleteWater"), repo.writes.map { it.first })
    }
}
