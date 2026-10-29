package com.vipin.fitness.feature.progress

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.Goal
import com.vipin.fitness.core.data.GoalProgressService
import com.vipin.fitness.core.data.GoalStatus
import com.vipin.fitness.core.data.GoalType
import com.vipin.fitness.core.data.NewGoal
import com.vipin.fitness.core.data.ProgressPoint
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeGoalsRepository
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

class GoalsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val goals = FakeGoalsRepository()
    private val progress = FakeProgressRepository()
    // Wed 21 Oct 2026, 23:50 IST.
    private val clock = MutableClock(Instant.parse("2026-10-21T18:20:00Z"))
    private val oct21 = LocalDate.of(2026, 10, 21)
    private fun vm() = GoalsViewModel(goals, GoalProgressService(goals, progress, clock), FakeUnitPreference(), clock)

    @Test fun progressUsesLatestWeightAndThisWeeksWorkouts() {
        goals.goals += Goal("g1", GoalType.LOSE_WEIGHT, GoalStatus.ACTIVE, 70.0, 80.0, null)
        goals.goals += Goal("g2", GoalType.WORKOUTS_PER_WEEK, GoalStatus.ACTIVE, 4.0, null, null)
        goals.goals += Goal("g3", GoalType.MAINTAIN, GoalStatus.ACHIEVED, 70.0, null, null)
        progress.points = mapOf(
            LocalDate.of(2026, 10, 1) to ProgressPoint(LocalDate.of(2026, 10, 1), weightKg = 79.0),
            LocalDate.of(2026, 10, 15) to ProgressPoint(LocalDate.of(2026, 10, 15), weightKg = 75.0, workoutsCompleted = 5), // last week
            LocalDate.of(2026, 10, 19) to ProgressPoint(LocalDate.of(2026, 10, 19), workoutsCompleted = 1), // Monday
            oct21 to ProgressPoint(oct21, workoutsCompleted = 2),
        )
        val s = vm().state.value
        assertEquals(0.5, s.active[0].fraction!!, 1e-9)
        assertEquals(50, s.active[0].milestone)
        assertEquals(0.75, s.active[1].fraction!!, 1e-9)
        assertEquals(listOf("g3"), s.past.map { it.goal.id })
        assertNull(s.past[0].fraction)
    }

    @Test fun summaryFailureStillListsGoals() {
        goals.goals += Goal("g1", GoalType.LOSE_WEIGHT, GoalStatus.ACTIVE, 70.0, 80.0, null)
        progress.summaryFailure = FAKE_NETWORK_FAILURE
        val s = vm().state.value
        assertNull(s.error)
        assertNull(s.active.single().fraction)
    }

    @Test fun createValidatesThenRetriesWithTheSameKey() {
        val vm = vm()
        vm.openNew()
        vm.onForm { it.copy(type = GoalType.LOSE_WEIGHT, target = "500", start = "") }
        vm.save()
        assertEquals(setOf(GoalFormError.TARGET, GoalFormError.START), vm.state.value.form!!.errors)
        vm.onForm { it.copy(target = "70", start = "80", date = "2026-12-31") }
        goals.failNext = FAKE_NETWORK_FAILURE
        vm.save()
        assertTrue(vm.state.value.writeError != null)
        vm.save()
        assertEquals(goals.writes[0].second, goals.writes[1].second)
        assertNull(vm.state.value.form)
        assertEquals(1, vm.state.value.active.size)
    }

    @Test fun statusAndDeleteReload() {
        goals.goals += Goal("g1", GoalType.WORKOUTS_PER_WEEK, GoalStatus.ACTIVE, 3.0, null, null)
        val vm = vm()
        vm.setStatus("g1", GoalStatus.ACHIEVED)
        assertEquals(GoalStatus.ACHIEVED, vm.state.value.past.single().goal.status)
        vm.delete("g1")
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test fun validationRules() {
        val today = oct21
        assertEquals(NewGoal(GoalType.WORKOUTS_PER_WEEK, 4.0, null, null), validate(GoalForm(GoalType.WORKOUTS_PER_WEEK, target = "4"), UnitSystem.METRIC, today).first)
        assertEquals(setOf(GoalFormError.TARGET), validate(GoalForm(GoalType.WORKOUTS_PER_WEEK, target = "8"), UnitSystem.METRIC, today).second)
        assertEquals(setOf(GoalFormError.DATE), validate(GoalForm(GoalType.IMPROVE_ENDURANCE, date = "2026-10-20"), UnitSystem.METRIC, today).second)
        val lb = validate(GoalForm(GoalType.MAINTAIN, target = "154.32"), UnitSystem.IMPERIAL, today).first!!
        assertEquals(70.0, lb.targetValue!!, 0.01)
    }
}
