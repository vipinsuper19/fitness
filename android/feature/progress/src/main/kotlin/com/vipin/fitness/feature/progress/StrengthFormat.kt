package com.vipin.fitness.feature.progress

import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.common.format.IndianNumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** en-IN numbers (lakh grouping, HALF_UP), weights in the user's units, dates in the profile zone. */
internal object StrengthFormat {
    private const val M_PER_MILE = 1609.344
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", ProgressFormat.LOCALE)

    fun number(v: Double, maxDecimals: Int = 1): String = IndianNumberFormat.number(v, maxDecimals)

    fun weightUnit(units: UnitSystem) = if (units == UnitSystem.IMPERIAL) "lb" else "kg"

    fun weight(kg: Double, units: UnitSystem): String {
        val v = if (units == UnitSystem.IMPERIAL) UnitConversion.kgToLb(kg) else kg
        return "${number(v)} ${weightUnit(units)}"
    }

    /** Volume (kg·reps) in the user's mass unit, whole numbers. */
    fun volume(kg: Double, units: UnitSystem): String {
        val v = if (units == UnitSystem.IMPERIAL) UnitConversion.kgToLb(kg) else kg
        return "${number(v, 0)} ${weightUnit(units)}"
    }

    fun reps(n: Int) = number(n.toDouble(), 0)

    /** "45 s", "12:05", "1:02:03". */
    fun duration(sec: Int): String {
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return when {
            h > 0 -> "%d:%02d:%02d".format(h, m, s)
            m > 0 -> "%d:%02d".format(m, s)
            else -> "$s s"
        }
    }

    /** Metric: "800 m" or "5.25 km"; imperial: "3.11 mi". */
    fun distance(m: Double, units: UnitSystem): String = when {
        units == UnitSystem.IMPERIAL -> "${number(m / M_PER_MILE, 2)} mi"
        m >= 1000 -> "${number(m / 1000, 2)} km"
        else -> "${number(m, 1)} m"
    }

    fun date(t: Instant, zone: ZoneId): String = t.atZone(zone).toLocalDate().format(dateFmt)
}
