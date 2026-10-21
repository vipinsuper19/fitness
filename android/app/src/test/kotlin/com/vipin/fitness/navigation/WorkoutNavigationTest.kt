package com.vipin.fitness.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutNavigationTest {
    @Test fun activationPopsBackToMain() {
        val stack = mutableListOf<Any>(Route.Main, Route.WorkoutNewPlan, Route.ExerciseLibrary(1), Route.PlanPreview)
        AppNavigation.onPlanActivated(stack)
        assertEquals(listOf<Any>(Route.Main), stack)
    }

    @Test fun activationWithoutMainResetsToMain() {
        val stack = mutableListOf<Any>(Route.PlanPreview)
        AppNavigation.onPlanActivated(stack)
        assertEquals(listOf<Any>(Route.Main), stack)
    }

    @Test fun popNeverEmpties() {
        val stack = mutableListOf<Any>(Route.Main, Route.ExerciseLibrary(2))
        AppNavigation.pop(stack)
        AppNavigation.pop(stack)
        assertEquals(listOf<Any>(Route.Main), stack)
    }
}
