package com.vipin.fitness.core.common

import java.math.BigDecimal
import java.math.RoundingMode

/** Feet + inches, for showing a height in imperial. */
data class FeetInches(val feet: Int, val inches: Double)

/**
 * Metric <-> imperial conversions. Canonical values (kg, cm, ml) are what we store and send;
 * convert only at the UI edge. Exact factors from the international yard and pound (1959) and
 * the US fluid ounce.
 */
object UnitConversion {
    const val KG_PER_LB = 0.45359237
    const val CM_PER_INCH = 2.54
    const val INCHES_PER_FOOT = 12
    const val ML_PER_US_FL_OZ = 29.5735295625

    fun kgToLb(kg: Double): Double = kg / KG_PER_LB
    fun lbToKg(lb: Double): Double = lb * KG_PER_LB

    fun cmToInches(cm: Double): Double = cm / CM_PER_INCH
    fun inchesToCm(inches: Double): Double = inches * CM_PER_INCH

    /**
     * Height in cm to feet + inches, with inches rounded to [inchDecimals] places. Rounding that
     * reaches 12 inches carries into the next foot (so 182.88 cm is 6 ft 0 in, never 5 ft 12 in).
     */
    fun cmToFeetInches(cm: Double, inchDecimals: Int = 0): FeetInches {
        require(cm >= 0) { "cm must be >= 0" }
        val scale = pow10(inchDecimals)
        // Decimal arithmetic: 171.45 cm is exactly 67.5 in, which must round up (a double gives 67.4999…).
        val totalScaledInches = BigDecimal.valueOf(cm).divide(CM_PER_INCH_DEC, 12, RoundingMode.HALF_UP)
            .setScale(inchDecimals, RoundingMode.HALF_UP).movePointRight(inchDecimals).intValueExact()
        val scaledPerFoot = INCHES_PER_FOOT * scale
        val feet = totalScaledInches / scaledPerFoot
        val inches = (totalScaledInches % scaledPerFoot).toDouble() / scale
        return FeetInches(feet, inches)
    }

    fun feetInchesToCm(feet: Int, inches: Double): Double {
        require(feet >= 0 && inches >= 0) { "feet and inches must be >= 0" }
        return BigDecimal.valueOf(inches).add(BigDecimal(feet * INCHES_PER_FOOT)).multiply(CM_PER_INCH_DEC).toDouble()
    }

    private val CM_PER_INCH_DEC = BigDecimal("2.54")

    fun mlToFlOz(ml: Double): Double = ml / ML_PER_US_FL_OZ
    fun flOzToMl(flOz: Double): Double = flOz * ML_PER_US_FL_OZ

    /** Weight for display in the user's units. Null (no data) stays null, never 0. */
    fun displayWeight(kg: Double?, units: UnitSystem): Double? =
        kg?.let { if (units == UnitSystem.METRIC) it else kgToLb(it) }

    /** User-entered weight back to canonical kg. */
    fun weightToKg(value: Double?, units: UnitSystem): Double? =
        value?.let { if (units == UnitSystem.METRIC) it else lbToKg(it) }

    private fun pow10(n: Int): Int {
        require(n in 0..3) { "inchDecimals must be 0..3" }
        var r = 1
        repeat(n) { r *= 10 }
        return r
    }
}
