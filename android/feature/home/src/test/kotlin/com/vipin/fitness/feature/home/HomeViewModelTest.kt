package com.vipin.fitness.feature.home

import com.vipin.fitness.core.data.HomePlan
import com.vipin.fitness.core.data.DailyNutrition
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.PlanWorkout
import com.vipin.fitness.core.data.ProgressPoint
import com.vipin.fitness.core.data.XpBalance
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeHomeRepository
import com.vipin.fitness.core.testing.FakeProgressRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val home = FakeHomeRepository()
    private val progress = FakeProgressRepository()
    // Wed 21 Oct 2026, 23:50 IST.
    private val clock = MutableClock(Instant.parse("2026-10-21T18:20:00Z"))
    private val oct21 = LocalDate.of(2026, 10, 21)
    private fun vm() = HomeViewModel(home, progress, FakeUnitPreference(), clock)
    private fun <T> Slot<T>.value() = (this as Slot.Ready<T>).value

    @Test fun emptyAccountShowsEmptyStatesNotErrors() {
        val s = vm().state.value
        assertNull(s.plan.value().plan)
        assertEquals(0, s.nutrition.value().entryCount)
        assertEquals(0, s.streak.value().days)
        assertEquals(0, s.xp.value().total)
    }

    @Test fun showsTodaysPlanDayOrRestDay() {
        home.plan = HomePlan("p1", "Push Pull Legs", 8, listOf(PlanWorkout(3, "Legs", 5), PlanWorkout(1, "Push", 6)))
        val d = vm().state.value.plan.value()
        assertEquals("Legs", d.workout!!.name) // 21 Oct 2026 is a Wednesday (ISO 3)
        home.plan = HomePlan("p1", "PPL", 8, listOf(PlanWorkout(1, "Push", 6)))
        val rest = vm().state.value.plan.value()
        assertEquals("PPL", rest.plan!!.name)
        assertNull(rest.workout)
    }

    @Test fun streakXpAndNutritionForToday() {
        progress.streakDays = 4
        progress.points = mapOf(oct21 to ProgressPoint(oct21, null, 1, 40, 1200.0, 30))
        home.xp = XpBalance(530, 3, 30, 200)
        home.nutrition = { d -> DailyNutrition(d, 3, Nutrients(1200.0, 60.0, 150.0, 40.0), MealType.entries.associateWith { Nutrients.ZERO }, 1500, 2000, 2500) }
        val s = vm().state.value
        assertEquals(Streak(4, 1, 30), s.streak.value())
        assertEquals(oct21 to oct21, progress.summaryCalls.single())
        assertEquals(listOf(oct21), home.nutritionCalls)
        assertEquals(1500, s.nutrition.value().waterMl)
        assertEquals(3, s.xp.value().level)
    }

    @Test fun eachSlotFailsOnItsOwn() {
        home.xpFailure = FAKE_NETWORK_FAILURE
        val s = vm().state.value
        assertTrue(s.xp is Slot.Failed)
        assertTrue(s.plan is Slot.Ready)
        assertTrue(s.nutrition is Slot.Ready)
    }

    @Test fun resumeAfterMidnightLoadsTheNewDay() {
        val vm = vm()
        vm.onResume() // right after the first load: no duplicate fetch
        assertEquals(1, home.nutritionCalls.size)
        clock.instant = Instant.parse("2026-10-21T18:40:00Z") // 00:10 IST, Thu 22 Oct
        vm.onResume()
        assertEquals(LocalDate.of(2026, 10, 22), vm.state.value.today)
        assertEquals(LocalDate.of(2026, 10, 22), home.nutritionCalls.last())
        assertEquals(LocalDate.of(2026, 10, 22) to LocalDate.of(2026, 10, 22), progress.summaryCalls.last())
    }

    @Test fun waterFormatsInUserUnits() {
        assertEquals("1,500 ml", HomeFormat.water(1500, com.vipin.fitness.core.common.UnitSystem.METRIC))
        assertEquals("50.7 fl oz", HomeFormat.water(1500, com.vipin.fitness.core.common.UnitSystem.IMPERIAL))
        assertEquals("3", HomeFormat.number(2.5, 0))
    }
}
