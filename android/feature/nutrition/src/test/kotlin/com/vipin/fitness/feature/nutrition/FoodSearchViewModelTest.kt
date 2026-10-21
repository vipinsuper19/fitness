package com.vipin.fitness.feature.nutrition

import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.ServingUnit
import com.vipin.fitness.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FoodSearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeNutritionRepository()
    private var n = 0
    private val day = LocalDate.of(2026, 10, 20)
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC)
    private fun vm() = FoodSearchViewModel(repo, clock).also {
        it.newKey = { "k${++n}" }
        it.start(day, MealType.LUNCH)
    }

    @Test fun searchTrimsAndPagesWithoutDuplicates() {
        val vm = vm()
        vm.onQuery("  poha ")
        vm.search()
        assertEquals("poha" to null, repo.searches.single())
        assertEquals("c2", vm.state.value.nextCursor)
        vm.loadMore()
        assertEquals(listOf("f1", "f2"), vm.state.value.results.map { it.id })
        assertNull(vm.state.value.nextCursor)
    }

    @Test fun blankQueryDoesNotSearch() {
        val vm = vm()
        vm.onQuery("   ")
        vm.search()
        assertTrue(repo.searches.isEmpty())
    }

    @Test fun addLogsServingsToChosenDayAndMeal() {
        val vm = vm()
        vm.select(food())
        assertEquals(150.0, vm.state.value.portion!!.grams())
        vm.onPortion { it.copy(servings = "1.5") }
        assertEquals(150.0, vm.state.value.portion!!.preview()!!.energyKcal, 1e-9)
        assertEquals(225.0, vm.state.value.portion!!.grams()!!, 1e-9)
        vm.onMeal(MealType.DINNER)
        vm.add()
        assertEquals(listOf("f1", day, MealType.DINNER, 1.5, null), repo.lastLog)
        assertEquals("logFood" to "k1", repo.writes.single())
        assertTrue(vm.state.value.added)
    }

    @Test fun addWithEditedMacrosSendsOverride() {
        val vm = vm()
        vm.select(food())
        vm.onPortion { it.copy(editMacros = true, macros = MacroForm("210", "6", "30", "4.5"), macrosEdited = true) }
        vm.add()
        assertEquals(Nutrients(210.0, 6.0, 30.0, 4.5), repo.lastLog!![4])
    }

    @Test fun invalidServingsBlockAddAndRetryReusesKey() {
        val vm = vm()
        vm.select(food())
        vm.onPortion { it.copy(servings = "") }
        vm.add()
        assertEquals(setOf(DiaryViewModel.FIELD_SERVINGS), vm.state.value.portion!!.errors)
        vm.onPortion { it.copy(servings = "2") }
        repo.failNext = NETWORK_FAILURE
        vm.add()
        assertNotNull(vm.state.value.error)
        vm.add()
        assertEquals(listOf("k1", "k1"), repo.writes.map { it.second })
    }

    @Test fun customFoodValidatesCreatesAndOpensPortion() {
        val vm = vm()
        vm.onQuery("Rajma")
        vm.startCustom()
        assertEquals("Rajma", vm.state.value.custom!!.name)
        vm.saveCustom()
        assertTrue(CustomFoodForm.F_MACROS in vm.state.value.custom!!.errors)
        vm.onCustom { it.copy(servingSize = "100", servingUnit = ServingUnit.G, macros = MacroForm("140", "9", "22", "0.5")) }
        vm.saveCustom()
        assertEquals(100.0, repo.lastFood!!.gramsPerServing) // grams unit: weight is the size
        assertEquals("createFood" to "k1", repo.writes.single())
        assertNull(vm.state.value.custom)
        assertEquals("new", vm.state.value.portion!!.food.id)
        assertEquals("new", vm.state.value.results.first().id)
    }

    @Test fun customFoodRejectsBadOptionalGrams() {
        val vm = vm()
        vm.startCustom()
        vm.onCustom {
            it.copy(name = "Thali", servingUnit = ServingUnit.PLATE, gramsPerServing = "0", macros = MacroForm("700", "20", "90", "25"))
        }
        vm.saveCustom()
        assertEquals(setOf(CustomFoodForm.F_GRAMS), vm.state.value.custom!!.errors)
        vm.onCustom { it.copy(gramsPerServing = "") }
        vm.saveCustom()
        assertNull(repo.lastFood!!.gramsPerServing)
    }

    @Test fun parsesEnInGroupedNumbers() {
        assertEquals(1234.5, "1,234.5".toDecimalOrNull())
        assertNull("-1".toDecimalOrNull())
        // Grouping style (lakh vs thousand) depends on the platform locale data; digits and decimals do not.
        assertEquals("123456.5", NutritionFormat.number(123456.5).replace(",", ""))
        assertEquals("2", NutritionFormat.number(2.0))
    }

    @Test fun loadMorePagesTheSearchedQueryNotTheEditedText() {
        val vm = vm()
        vm.onQuery("poha")
        vm.search()
        vm.onQuery("poha ")  // same query once trimmed: paging stays available
        assertEquals("c2", vm.state.value.nextCursor)
        vm.loadMore()
        assertEquals("poha" to "c2", repo.searches.last())
        vm.onQuery("poha")
        vm.search()
        vm.onQuery("upma")
        assertNull(vm.state.value.nextCursor)
        vm.loadMore()
        assertEquals(3, repo.searches.size)
        assertTrue(repo.searches.none { it.first == "upma" })
    }

    @Test fun macroToggleWithoutEditingThenServingsChangeSendsNoOverride() {
        val vm = vm()
        vm.select(food())
        vm.onPortion { it.copy(editMacros = true, macros = MacroForm.from(it.food.nutrients)) }
        vm.onPortion { it.copy(servings = "2") }
        assertEquals(200.0, vm.state.value.portion!!.preview()!!.energyKcal, 0.0)
        vm.add()
        assertEquals(listOf("f1", day, MealType.LUNCH, 2.0, null), repo.lastLog)
    }

    @Test fun formattingRoundsHalfUp() {
        assertEquals("3", NutritionFormat.number(2.5, 0))
        assertEquals("3 kcal", NutritionFormat.kcal(2.5))
        assertEquals("1.5", NutritionFormat.number(1.45))
    }
}
