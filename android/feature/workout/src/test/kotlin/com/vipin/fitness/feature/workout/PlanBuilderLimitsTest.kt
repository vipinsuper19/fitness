package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.MONDAY

/** Contract limits for planned exercises: sets 1–20, reps 1–100, duration 1–7200 s. */
class PlanBuilderLimitsTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = PlanBuilderStore()
    private fun vm() = NewPlanViewModel(FakeWorkoutRepository(), store, FakeProfileRepository(Profile()))
    private val reps = testExercise("squat")
    private val timed = testExercise("plank", tracking = TrackingType.DURATION)

    @Test fun boundsAreInclusiveAndCheckedPerField() {
        fun e(sets: Int = 3, lo: Int? = 8, hi: Int? = 12) = CustomExercise(reps, sets = sets, repsMin = lo, repsMax = hi).fieldErrors()
        assertTrue(e(sets = 1, lo = 1, hi = 1).isEmpty())
        assertTrue(e(sets = 20, lo = 100, hi = 100).isEmpty())
        assertEquals(mapOf(ExerciseField.SETS to ExerciseFieldError.SETS_RANGE), e(sets = 0))
        assertEquals(mapOf(ExerciseField.SETS to ExerciseFieldError.SETS_RANGE), e(sets = 21))
        assertEquals(mapOf(ExerciseField.REPS_MIN to ExerciseFieldError.REPS_RANGE), e(lo = 0))
        assertEquals(mapOf(ExerciseField.REPS_MIN to ExerciseFieldError.REPS_RANGE), e(lo = null))
        assertEquals(mapOf(ExerciseField.REPS_MAX to ExerciseFieldError.REPS_RANGE), e(hi = 101))
        assertEquals(mapOf(ExerciseField.REPS_MAX to ExerciseFieldError.REPS_ORDER), e(lo = 12, hi = 8))
        // Out-of-range "from" doesn't also flag the order on "to".
        assertEquals(mapOf(ExerciseField.REPS_MIN to ExerciseFieldError.REPS_RANGE), e(lo = 101, hi = 100))
        fun d(s: Int?) = CustomExercise(timed, durationSeconds = s).fieldErrors()
        assertTrue(d(1).isEmpty()); assertTrue(d(7200).isEmpty())
        listOf(0, 7201, 9999, null).forEach { assertEquals("$it", mapOf(ExerciseField.DURATION to ExerciseFieldError.DURATION_RANGE), d(it)) }
        // Reps aren't checked on a timed exercise, nor duration on a reps one.
        assertTrue(CustomExercise(timed, repsMin = 500).fieldErrors().isEmpty())
    }

    @Test fun vmBlocksPreviewWhileAValueIsOutOfRangeAndAllowsItOnceFixed() {
        val vm = vm()
        vm.setCustomName("Mine")
        store.addExercise(MONDAY, reps, "Monday")
        store.addExercise(MONDAY, timed, "Monday")
        vm.updateExercise(MONDAY, 0) { it.copy(sets = 25) }
        vm.updateExercise(MONDAY, 1) { it.copy(durationSeconds = 9000) }
        assertTrue(store.custom.value.hasFieldErrors())
        vm.previewCustom()
        assertFalse(vm.state.value.openPreview)
        assertNull(store.preview.value)
        assertEquals(setOf(CustomPlanError.BAD_SETS, CustomPlanError.BAD_DURATION), vm.state.value.customErrors)
        vm.updateExercise(MONDAY, 0) { it.copy(sets = 20) }
        vm.updateExercise(MONDAY, 1) { it.copy(durationSeconds = 7200) }
        assertFalse(store.custom.value.hasFieldErrors())
        vm.previewCustom()
        assertTrue(vm.state.value.openPreview)
        val ex = store.preview.value!!.workouts.single().exercises
        assertEquals(listOf(20, 3), ex.map { it.sets })
        assertEquals(7200, ex[1].durationSeconds)
    }
}

@RunWith(AndroidJUnit4::class)
class PlanBuilderLimitsUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = PlanBuilderStore()

    private fun show(): NewPlanViewModel {
        val vm = NewPlanViewModel(FakeWorkoutRepository(), store, FakeProfileRepository(Profile()))
        vm.setCustomName("Mine")
        store.addExercise(MONDAY, testExercise("squat"), "Monday")
        store.addExercise(MONDAY, testExercise("plank", tracking = TrackingType.DURATION), "Monday")
        compose.setContent {
            val plan by vm.custom.collectAsState()
            val s by vm.state.collectAsState()
            Column(Modifier.verticalScroll(rememberScrollState())) { CustomForm(plan, s.customErrors, vm) {} }
        }
        return vm
    }

    private fun f(i: Int, field: ExerciseField) = PlanBuilderTags.field(MONDAY, i, field)
    private fun node(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun present(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun type(t: String, v: String) { compose.onNodeWithTag(t).performTextReplacement(v); compose.waitForIdle() }

    @Test fun eachFieldShowsItsOwnMessageAndPreviewIsDisabledUntilFixed() {
        show()
        compose.onNodeWithTag(PlanBuilderTags.PREVIEW).assertIsEnabled()
        type(f(0, ExerciseField.SETS), "21")
        node(PlanBuilderTags.error(f(0, ExerciseField.SETS))).assertTextContains("1 to 20")
        compose.onNodeWithTag(PlanBuilderTags.PREVIEW).assertIsNotEnabled()
        type(f(0, ExerciseField.SETS), "20")
        assertFalse(present(PlanBuilderTags.error(f(0, ExerciseField.SETS))))

        type(f(0, ExerciseField.REPS_MAX), "101")
        node(PlanBuilderTags.error(f(0, ExerciseField.REPS_MAX))).assertTextContains("1 to 100")
        type(f(0, ExerciseField.REPS_MAX), "5") // below "from" (8)
        node(PlanBuilderTags.error(f(0, ExerciseField.REPS_MAX))).assertTextContains("At least \"from\"")
        type(f(0, ExerciseField.REPS_MAX), "12")
        type(f(0, ExerciseField.REPS_MIN), "0")
        node(PlanBuilderTags.error(f(0, ExerciseField.REPS_MIN))).assertTextContains("1 to 100")
        type(f(0, ExerciseField.REPS_MIN), "8")

        type(f(1, ExerciseField.DURATION), "7201")
        node(PlanBuilderTags.error(f(1, ExerciseField.DURATION))).assertTextContains("1 to 7200 s")
        compose.onNodeWithTag(PlanBuilderTags.PREVIEW).assertIsNotEnabled()
        type(f(1, ExerciseField.DURATION), "7200")
        compose.onNodeWithTag(PlanBuilderTags.PREVIEW).assertIsEnabled()
    }

    @Test fun clearedSetsShowsEmptyWithTheMinimumMessage() {
        show()
        type(f(0, ExerciseField.SETS), "")
        assertEquals(0, store.custom.value.days.getValue(MONDAY).exercises[0].sets)
        node(PlanBuilderTags.error(f(0, ExerciseField.SETS))).assertTextContains("1 to 20")
        compose.onNodeWithTag(PlanBuilderTags.PREVIEW).assertIsNotEnabled()
    }
}
