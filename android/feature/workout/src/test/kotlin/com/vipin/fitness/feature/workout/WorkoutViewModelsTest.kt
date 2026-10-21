package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.data.ExperienceLevel
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.data.workout.Equipment
import com.vipin.fitness.core.data.workout.ExercisePage
import com.vipin.fitness.core.data.workout.MuscleGroup
import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlannedExercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.asPlan
import com.vipin.fitness.core.testing.networkFailure
import com.vipin.fitness.core.testing.testExercise
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import com.vipin.fitness.core.data.workout.ExerciseFilter

private class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    override fun instant(): Instant = now
}

private val draft = PlanDraft(
    "Starter", 4, PlanSource.RULE_BASED, "Because",
    listOf(PlannedWorkout(DayOfWeek.MONDAY, "Full body", listOf(PlannedExercise("squat", 3, 8, 12)))),
)

class WorkoutHomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeWorkoutRepository().apply { catalog["squat"] = testExercise("squat", "Squat") }
    // Wednesday 2026-10-21, 10:00 IST
    private val clock = Clock.fixed(Instant.parse("2026-10-21T04:30:00Z"), ZoneId.of("Asia/Kolkata"))

    @Test fun noPlanShowsEmptyWeekStartingMonday() {
        val vm = WorkoutHomeViewModel(repo, clock)
        val s = vm.state.value
        assertNull(s.plan)
        assertFalse(s.loading)
        assertEquals(LocalDate.of(2026, 10, 19), s.week.first().date)
        assertEquals(DayOfWeek.SUNDAY, s.week.last().day)
        assertEquals(DayOfWeek.WEDNESDAY, s.week.single { it.isToday }.day)
        assertEquals(DayOfWeek.WEDNESDAY, s.selectedDay)
    }

    @Test fun activePlanFillsCalendarAndNames() {
        repo.refreshResult = { ApiResult.Success(ActivePlan.Some(draft.asPlan("p1", true))) }
        val s = WorkoutHomeViewModel(repo, clock).state.value
        assertEquals("Full body", s.week[0].workout?.name)
        assertNull(s.week[1].workout)
        assertEquals(mapOf("squat" to "Squat"), s.exerciseNames)
    }

    @Test fun failureWithoutPlanIsErrorWithPlanIsStale() {
        repo.refreshResult = { networkFailure }
        val vm = WorkoutHomeViewModel(repo, clock)
        assertNotNull(vm.state.value.error)
        repo.activePlan.value = ActivePlan.Some(draft.asPlan("p1", true))
        assertNull(vm.state.value.error)
        vm.refresh()
        assertTrue(vm.state.value.stale)
        assertEquals("p1", vm.state.value.plan?.id)
    }

    @Test fun todayIsInIndiaNotUtc() {
        // 20:00 UTC on the 20th is 01:30 IST on Wednesday the 21st.
        val utc = Clock.fixed(Instant.parse("2026-10-20T20:00:00Z"), ZoneOffset.UTC)
        val s = WorkoutHomeViewModel(repo, utc).state.value
        assertEquals(LocalDate.of(2026, 10, 21), s.week.single { it.isToday }.date)
        assertEquals(DayOfWeek.WEDNESDAY, s.selectedDay)
    }

    @Test fun resumeOnANewDayRebuildsWeekAndToday() {
        val clock = MutableClock(Instant.parse("2026-10-25T10:00:00Z")) // Sunday IST
        val vm = WorkoutHomeViewModel(repo, clock)
        assertEquals(LocalDate.of(2026, 10, 19), vm.state.value.week.first().date)
        clock.now = Instant.parse("2026-10-25T19:00:00Z") // 00:30 IST Monday 26th
        vm.onResume()
        val s = vm.state.value
        assertEquals(LocalDate.of(2026, 10, 26), s.week.first().date)
        assertEquals(DayOfWeek.MONDAY, s.selectedDay)
        assertTrue(s.week.first().isToday)
    }

    @Test fun resumeKeepsAPickedDay() {
        val clock = MutableClock(Instant.parse("2026-10-21T10:00:00Z"))
        val vm = WorkoutHomeViewModel(repo, clock)
        vm.select(DayOfWeek.FRIDAY)
        clock.now = Instant.parse("2026-10-22T10:00:00Z")
        vm.onResume()
        assertEquals(DayOfWeek.FRIDAY, vm.state.value.selectedDay)
        assertEquals(DayOfWeek.THURSDAY, vm.state.value.week.single { it.isToday }.day)
    }

    @Test fun followsActivationElsewhere() {
        val vm = WorkoutHomeViewModel(repo, clock)
        assertNull(vm.state.value.plan)
        repo.activePlan.value = ActivePlan.Some(draft.asPlan("p2", true))
        assertEquals("p2", vm.state.value.plan?.id)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseLibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val repo = FakeWorkoutRepository()
    private val store = PlanBuilderStore()

    @Test fun typingIsDebouncedChipsAreNot() = runTest(main.dispatcher) {
        val vm = ExerciseLibraryViewModel(repo, store)
        advanceTimeBy(301); runCurrent()
        assertEquals(1, repo.exerciseCalls.size)
        vm.onQuery("s"); vm.onQuery("sq"); vm.onQuery("squat")
        advanceTimeBy(200); runCurrent()
        assertEquals(1, repo.exerciseCalls.size)
        advanceTimeBy(101); runCurrent()
        assertEquals(2, repo.exerciseCalls.size)
        assertEquals("squat", repo.exerciseCalls.last().first.query)
        vm.onMuscle(MuscleGroup.entries.first()); runCurrent()
        assertEquals(3, repo.exerciseCalls.size)
        assertEquals(MuscleGroup.entries.first(), repo.exerciseCalls.last().first.muscle)
        vm.onMuscle(MuscleGroup.entries.first()); runCurrent() // tap again clears
        assertNull(repo.exerciseCalls.last().first.muscle)
        vm.onEquipment(Equipment.DUMBBELL); runCurrent()
        assertEquals(Equipment.DUMBBELL, vm.state.value.filter.equipment)
    }

    @Test fun loadMoreAppendsWithoutDuplicates() = runTest(main.dispatcher) {
        repo.pages = { _, c ->
            if (c == null) ApiResult.Success(ExercisePage(listOf(testExercise("a"), testExercise("b")), "n1"))
            else ApiResult.Success(ExercisePage(listOf(testExercise("b"), testExercise("c")), null))
        }
        val vm = ExerciseLibraryViewModel(repo, store)
        advanceTimeBy(301); runCurrent()
        vm.loadMore(); vm.loadMore(); runCurrent()
        assertEquals(listOf("a", "b", "c"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.nextCursor)
        assertEquals(2, repo.exerciseCalls.size)
        vm.loadMore(); runCurrent()
        assertEquals(2, repo.exerciseCalls.size)
    }

    @Test fun loadMoreDuringDebounceUsesTheLoadedFilter() = runTest(main.dispatcher) {
        repo.pages = { _, c -> ApiResult.Success(ExercisePage(listOf(testExercise(c ?: "a")), if (c == null) "n1" else null)) }
        val vm = ExerciseLibraryViewModel(repo, store)
        vm.onQuery("squat")
        advanceTimeBy(301); runCurrent()
        vm.onQuery("bench") // still inside the debounce window
        vm.loadMore(); runCurrent()
        assertEquals(ExerciseFilter(query = "squat") to "n1", repo.exerciseCalls.last())
        advanceTimeBy(301); runCurrent()
        assertEquals(ExerciseFilter(query = "bench") to null, repo.exerciseCalls.last())
        assertEquals(ExerciseFilter(query = "bench"), vm.state.value.loadedFilter)
    }

    @Test fun pickAddsOnceToTheDay() = runTest(main.dispatcher) {
        val vm = ExerciseLibraryViewModel(repo, store)
        vm.pick(testExercise("a")) // not in pick mode
        assertTrue(store.custom.value.days.isEmpty())
        vm.setPickDay(DayOfWeek.TUESDAY)
        vm.pick(testExercise("a")); vm.pick(testExercise("b"))
        assertTrue(vm.state.value.picked)
        assertEquals(listOf("a"), store.custom.value.days[DayOfWeek.TUESDAY]!!.exercises.map { it.exercise.id })
    }

    @Test fun failureShowsError() = runTest(main.dispatcher) {
        repo.pages = { _, _ -> networkFailure }
        val vm = ExerciseLibraryViewModel(repo, store)
        advanceTimeBy(301); runCurrent()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }
}

class NewPlanViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeWorkoutRepository()
    private val store = PlanBuilderStore()
    private val profiles = FakeProfileRepository(Profile(experienceLevel = ExperienceLevel.INTERMEDIATE))

    @Test fun prefillsExperienceFromProfile() {
        assertEquals(ExperienceLevel.INTERMEDIATE, NewPlanViewModel(repo, store, profiles).state.value.form.experience)
    }

    @Test fun formIsClamped() {
        val vm = NewPlanViewModel(repo, store, profiles)
        vm.onForm { it.copy(daysPerWeek = 9, sessionMinutes = 5, weeks = 0) }
        val f = vm.state.value.form
        assertEquals(listOf(7, 15, 1), listOf(f.daysPerWeek, f.sessionMinutes, f.weeks))
    }

    @Test fun generateSuccessOpensPreviewOnceAndIgnoresDoubleTap() {
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        repo.generateResult = { ApiResult.Success(draft) }
        val vm = NewPlanViewModel(repo, store, profiles)
        vm.toggleEquipment(Equipment.DUMBBELL)
        vm.generate(); vm.generate()
        assertTrue(vm.state.value.generating)
        gate.complete(Unit)
        assertEquals(1, repo.generateCalls.size)
        assertEquals(setOf(Equipment.DUMBBELL), repo.generateCalls.single().equipment)
        assertEquals(draft, store.preview.value)
        assertTrue(vm.state.value.openPreview)
        vm.onNavigatedToPreview()
        assertFalse(vm.state.value.openPreview)
    }

    @Test fun generateFailureShowsError() {
        val vm = NewPlanViewModel(repo, store, profiles)
        vm.generate()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.generating)
        assertNull(store.preview.value)
    }

    @Test fun customPreviewValidates() {
        val vm = NewPlanViewModel(repo, store, profiles)
        vm.previewCustom()
        assertTrue(CustomPlanError.NO_DAYS in vm.state.value.customErrors)
        assertFalse(vm.state.value.openPreview)
        vm.setCustomName("Mine")
        assertTrue(vm.state.value.customErrors.isEmpty())
        store.addExercise(DayOfWeek.MONDAY, testExercise("a"), "Monday")
        vm.previewCustom()
        assertTrue(vm.state.value.openPreview)
        assertEquals(PlanSource.CUSTOM, store.preview.value?.source)
    }
}

class PlanPreviewViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeWorkoutRepository().apply { catalog["squat"] = testExercise("squat", "Squat") }
    private val store = PlanBuilderStore().apply { setPreview(draft) }
    private var n = 0
    private fun vm() = PlanPreviewViewModel(repo, store).also { it.newKey = { "k${++n}" } }

    @Test fun createsThenActivates() {
        val vm = vm()
        assertEquals(mapOf("squat" to "Squat"), vm.state.value.exerciseNames)
        vm.saveAndActivate()
        assertEquals(listOf("k1"), repo.createKeys)
        assertEquals(listOf("p1" to "k2"), repo.activateCalls)
        assertTrue(vm.state.value.done)
        assertNull(store.preview.value)
        assertEquals("p1", (repo.activePlan.value as ActivePlan.Some).plan.id)
    }

    @Test fun retryAfterActivateFailureOnlyActivatesWithSameKey() {
        var fail = true
        repo.activateResult = { if (fail) networkFailure else null }
        val vm = vm()
        vm.saveAndActivate()
        assertNotNull(vm.state.value.error)
        fail = false
        vm.saveAndActivate()
        assertEquals(1, repo.createKeys.size)
        assertEquals(listOf("p1" to "k2", "p1" to "k2"), repo.activateCalls)
        assertTrue(vm.state.value.done)
    }

    @Test fun retryAfterCreateFailureReusesCreateKey() {
        var fail = true
        repo.createResult = { if (fail) networkFailure else ApiResult.Success(it.asPlan("p9")) }
        val vm = vm()
        vm.saveAndActivate()
        fail = false
        vm.saveAndActivate()
        assertEquals(listOf("k1", "k1"), repo.createKeys)
        assertEquals("p9", repo.activateCalls.single().first)
    }

    @Test fun reopenedPreviewAfterActivateFailureOnlyActivates() {
        var fail = true
        repo.activateResult = { if (fail) networkFailure else null }
        vm().saveAndActivate() // created p1, activate failed; user leaves Preview
        fail = false
        val reopened = vm() // a new ViewModel, same store
        reopened.saveAndActivate()
        assertEquals(listOf("k1"), repo.createKeys)
        assertEquals(listOf("p1" to "k2", "p1" to "k2"), repo.activateCalls)
        assertTrue(reopened.state.value.done)
        assertEquals(SaveProgress(), store.saveProgress.value)
    }

    @Test fun newDraftStartsANewSave() {
        repo.activateResult = { networkFailure }
        vm().saveAndActivate()
        store.setPreview(draft.copy(name = "Other"))
        repo.activateResult = { null }
        vm().saveAndActivate()
        assertEquals(2, repo.createKeys.distinct().size)
    }

    @Test fun doubleTapCreatesOnce() {
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val vm = vm()
        vm.saveAndActivate(); vm.saveAndActivate()
        gate.complete(Unit)
        assertEquals(1, repo.createKeys.size)
        vm.saveAndActivate() // done: ignored
        assertEquals(1, repo.activateCalls.size)
    }

    @Test fun customPlanIsResetAfterActivation() {
        store.addExercise(DayOfWeek.MONDAY, testExercise("a"), "Monday")
        store.setPreview(draft.copy(source = PlanSource.CUSTOM))
        vm().saveAndActivate()
        assertTrue(store.custom.value.days.isEmpty())
    }
}
