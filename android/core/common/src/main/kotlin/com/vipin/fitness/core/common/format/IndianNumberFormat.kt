package com.vipin.fitness.core.common.format

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The app's one number formatter (India first, independent of the device locale):
 * - rounds the **decimal** value half-up (`BigDecimal.valueOf`), so 1.45 → 1.5 and 2.5 → 3 at 0 places;
 * - drops trailing zeros (2.50 → 2.5, 3.0 → 3);
 * - en-IN lakh grouping: the last three digits, then pairs (1,00,000 and 12,34,567.8); the JDK only groups in threes;
 * - never shows "-0": anything that rounds to zero prints as "0".
 * Unit symbols are the caller's job.
 */
object IndianNumberFormat {
    /** [v] half-up to at most [maxDecimals] places, lakh grouped. NaN/infinite print as "–" rather than crashing. */
    fun number(v: Double, maxDecimals: Int = 1): String {
        if (!v.isFinite()) return "\u2013"
        return number(BigDecimal.valueOf(v), maxDecimals)
    }

    fun number(v: BigDecimal, maxDecimals: Int = 1): String {
        require(maxDecimals >= 0) { "maxDecimals must be >= 0" }
        val rounded = v.setScale(maxDecimals, RoundingMode.HALF_UP)
        if (rounded.signum() == 0) return "0"
        return group(rounded.stripTrailingZeros().toPlainString())
    }

    fun integer(v: Long): String = number(BigDecimal.valueOf(v), 0)

    fun integer(v: Int): String = integer(v.toLong())

    /** Lakh grouping of a plain decimal string such as "-1234567.5". */
    internal fun group(plain: String): String {
        val neg = plain.startsWith("-")
        val body = plain.removePrefix("-")
        val int = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = if (int.length <= 3) int else {
            val head = int.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + int.takeLast(3)
        }
        return (if (neg) "-" else "") + grouped + if (frac.isNotEmpty()) ".$frac" else ""
    }
}
