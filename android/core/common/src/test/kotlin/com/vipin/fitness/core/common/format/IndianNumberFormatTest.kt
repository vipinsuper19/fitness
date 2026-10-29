package com.vipin.fitness.core.common.format

import com.vipin.fitness.core.common.format.IndianNumberFormat.integer
import com.vipin.fitness.core.common.format.IndianNumberFormat.number
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class IndianNumberFormatTest {
    @Test fun lakhAndCroreGrouping() {
        assertEquals("0", number(0.0))
        assertEquals("999", number(999.0))
        assertEquals("1,000", number(1000.0))
        assertEquals("99,999", number(99999.0))
        assertEquals("1,00,000", number(100000.0))
        assertEquals("12,34,567", number(1234567.0))
        assertEquals("1,00,00,000", number(10000000.0))
        assertEquals("12,34,56,78,901", integer(123456789012L / 10))
    }

    @Test fun halfUpOnTheDecimalValue() {
        assertEquals("1.5", number(1.45))   // the double 1.45 is 1.4499…; the decimal value rounds up
        assertEquals("2.5", number(2.45))
        assertEquals("3", number(2.5, 0))
        assertEquals("2", number(1.5, 0))
        assertEquals("1", number(0.5, 0))
        assertEquals("0.13", number(0.125, 2))
        assertEquals("1,00,000", number(99999.95))
        assertEquals("12,34,567.5", number(1234567.45))
    }

    @Test fun trailingZerosDropped() {
        assertEquals("2.5", number(2.50, 2))
        assertEquals("3", number(3.0, 2))
        assertEquals("100", number(100.0, 2)) // not "1E+2"
        assertEquals("1,000", number(BigDecimal("1000.000"), 3))
    }

    @Test fun neverMinusZero() {
        assertEquals("0", number(-0.0))
        assertEquals("0", number(-0.04))
        assertEquals("0", number(-0.4, 0))
        assertEquals("0", number(-0.004, 2))
        assertEquals("0", integer(0))
    }

    @Test fun negatives() {
        assertEquals("-0.1", number(-0.05))
        assertEquals("-1,00,000", number(-100000.0))
        assertEquals("-1,234.5", number(-1234.45))
        assertEquals("-20", integer(-20))
    }

    @Test fun integersAndExtremes() {
        assertEquals("1,250", integer(1250))
        assertEquals("2,14,74,83,647", integer(Int.MAX_VALUE))
        assertEquals("-92,23,37,20,36,85,47,75,808", integer(Long.MIN_VALUE))
        assertEquals("\u2013", number(Double.NaN))
        assertEquals("\u2013", number(Double.POSITIVE_INFINITY))
    }

    @Test(expected = IllegalArgumentException::class) fun negativeDecimalsRejected() {
        number(1.0, -1)
    }
}
