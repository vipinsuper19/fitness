package com.vipin.fitness.feature.workout

import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.testing.testExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY

class PlanBuilderStoreTest {
    private val store = PlanBuilderStore()

    @Test fun emptyPlanReportsNameAndNoDays() {
        assertEquals(setOf(CustomPlanError.NAME, CustomPlanError.NO_DAYS), store.custom.value.errors())
    }

    @Test fun dayWithoutExercisesIsInvalid() {
        store.setName("Mine")
        store.toggleDay(MONDAY, "Monday")
        assertEquals(setOf(CustomPlanError.EMPTY_DAY), store.custom.value.errors())
    }

    @Test fun addingToUnselectedDaySelectsItAndDefaultsByTrackingType() {
        store.addExercise(FRIDAY, testExercise("plank", tracking = TrackingType.DURATION), "Friday")
        store.addExercise(MONDAY, testExercise("squat"), "Monday")
        val p = store.custom.value
        assertEquals(listOf(MONDAY, FRIDAY), p.days.keys.toList())
        assertEquals(60, p.days[FRIDAY]!!.exercises[0].durationSeconds)
        assertNull(p.days[FRIDAY]!!.exercises[0].repsMin)
        assertEquals(8, p.days[MONDAY]!!.exercises[0].repsMin)
    }

    @Test fun badValuesAreFlagged() {
        store.setName("Mine")
        store.addExercise(MONDAY, testExercise("squat"), "Monday")
        store.addExercise(MONDAY, testExercise("plank", tracking = TrackingType.DURATION), "Monday")
        store.updateExercise(MONDAY, 0) { it.copy(sets = 0, repsMin = 12, repsMax = 8) }
        store.updateExercise(MONDAY, 1) { it.copy(durationSeconds = null) }
        assertEquals(
            setOf(CustomPlanError.BAD_SETS, CustomPlanError.BAD_REPS, CustomPlanError.BAD_DURATION),
            store.custom.value.errors(),
        )
    }

    @Test fun moveRemoveAndDraftOrder() {
        store.setName("  Mine ")
        listOf("a", "b", "c").forEach { store.addExercise(MONDAY, testExercise(it), "Monday") }
        store.moveExercise(MONDAY, 2, -1)
        store.moveExercise(MONDAY, 0, -1) // out of range: ignored
        store.removeExercise(MONDAY, 0)
        val draft = store.custom.value.toDraft()
        assertTrue(store.custom.value.errors().isEmpty())
        assertEquals("Mine", draft.name)
        assertEquals(PlanSource.CUSTOM, draft.source)
        assertEquals(listOf("c", "b"), draft.workouts.single().exercises.map { it.exerciseId })
    }

    @Test fun durationExerciseDropsRepsInDraft() {
        store.setName("x")
        store.addExercise(MONDAY, testExercise("plank", tracking = TrackingType.DURATION), "Monday")
        store.updateExercise(MONDAY, 0) { it.copy(repsMin = 5) }
        val e = store.custom.value.toDraft().workouts.single().exercises.single()
        assertNull(e.repsMin)
        assertEquals(60, e.durationSeconds)
    }

    @Test fun cappedAtThirtyExercises() {
        repeat(35) { store.addExercise(MONDAY, testExercise("e$it"), "Monday") }
        assertEquals(PlanBuilderStore.MAX_EXERCISES, store.custom.value.days[MONDAY]!!.exercises.size)
    }
}
