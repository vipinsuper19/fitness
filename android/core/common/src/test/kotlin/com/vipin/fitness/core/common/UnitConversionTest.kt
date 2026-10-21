package com.vipin.fitness.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitConversionTest {
    private val eps = 1e-9

    @Test fun weight() {
        assertEquals(1.0, UnitConversion.lbToKg(1 / 0.45359237), eps)
        assertEquals(220.46226218487757, UnitConversion.kgToLb(100.0), eps)
        assertEquals(70.0, UnitConversion.lbToKg(UnitConversion.kgToLb(70.0)), eps)
    }

    @Test fun lengthAndHeight() {
        assertEquals(2.54, UnitConversion.inchesToCm(1.0), eps)
        assertEquals(FeetInches(5, 9.0), UnitConversion.cmToFeetInches(175.26))
        assertEquals(FeetInches(5, 9.0), UnitConversion.cmToFeetInches(175.0)) // 68.9 in rounds to 69
        assertEquals(FeetInches(5, 8.9), UnitConversion.cmToFeetInches(175.0, inchDecimals = 1))
        assertEquals(175.26, UnitConversion.feetInchesToCm(5, 9.0), eps)
    }

    @Test fun roundingCarriesIntoNextFoot() {
        // 182.8 cm = 71.97 in -> rounds to 72 -> 6 ft 0 in, not 5 ft 12 in.
        assertEquals(FeetInches(6, 0.0), UnitConversion.cmToFeetInches(182.8))
        assertEquals(FeetInches(0, 0.0), UnitConversion.cmToFeetInches(0.0))
    }

    @Test fun volume() {
        assertEquals(29.5735295625, UnitConversion.flOzToMl(1.0), eps)
        assertEquals(250.0, UnitConversion.flOzToMl(UnitConversion.mlToFlOz(250.0)), eps)
    }

    @Test fun displayKeepsNullAsNoData() {
        assertNull(UnitConversion.displayWeight(null, UnitSystem.IMPERIAL))
        assertNull(UnitConversion.weightToKg(null, UnitSystem.METRIC))
        assertEquals(80.0, UnitConversion.displayWeight(80.0, UnitSystem.METRIC)!!, eps)
        assertEquals(80.0, UnitConversion.weightToKg(UnitConversion.displayWeight(80.0, UnitSystem.IMPERIAL), UnitSystem.IMPERIAL)!!, eps)
    }

    @Test(expected = IllegalArgumentException::class) fun negativeHeightRejected() {
        UnitConversion.cmToFeetInches(-1.0)
    }

    @Test fun exactHalfInchRoundsUp() {
        assertEquals(FeetInches(5, 8.0), UnitConversion.cmToFeetInches(171.45))
        assertEquals(171.45, UnitConversion.feetInchesToCm(5, 7.5), 0.0)
    }
}
