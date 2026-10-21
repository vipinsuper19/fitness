package com.vipin.fitness.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NutrientsRoundingTest {
    @Test fun rounded1dpRoundsDecimalHalfUp() {
        // 1.45 as a double is 1.4499999…; rounding must use its decimal value.
        val n = Nutrients(1.45, 2.25, 0.05, 10.0, fiberG = 1.45).rounded1dp()
        assertEquals(1.5, n.energyKcal, 0.0)
        assertEquals(2.3, n.proteinG, 0.0)
        assertEquals(0.1, n.carbsG, 0.0)
        assertEquals(10.0, n.fatG, 0.0)
        assertEquals(1.5, n.fiberG!!, 0.0)
    }
}
