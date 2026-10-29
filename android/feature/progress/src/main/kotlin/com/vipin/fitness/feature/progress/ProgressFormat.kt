package com.vipin.fitness.feature.progress

import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.MeasureDimension
import com.vipin.fitness.core.data.MeasurementKind
import java.math.BigDecimal
import java.math.RoundingMode
import com.vipin.fitness.core.common.format.IndianNumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** India first: en-IN formatting, dates in Asia/Kolkata, half-up rounding (same rules as nutrition). */
internal object ProgressFormat {
    val LOCALE: Locale = Locale.forLanguageTag("en-IN")
    val ZONE: ZoneId = APP_ZONE
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", LOCALE)
    private val shortDateFmt = DateTimeFormatter.ofPattern("d MMM", LOCALE)

    /** Shared en-IN formatter: half-up on the decimal value, lakh grouping, never "-0". */
    fun number(v: Double, maxDecimals: Int = 1): String = IndianNumberFormat.number(v, maxDecimals)

    /** Plain number for an input field: no grouping, up to [decimals] places, half-up. */
    fun input(v: Double, decimals: Int = 1): String = BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun date(d: LocalDate): String = d.format(dateFmt)
    fun shortDate(d: LocalDate): String = d.format(shortDateFmt)
    fun unitLabel(kind: MeasurementKind, units: UnitSystem): String = unitLabel(kind.dimension, units)
    fun unitLabel(dim: MeasureDimension, units: UnitSystem): String = when (dim) {
        MeasureDimension.MASS -> if (units == UnitSystem.METRIC) "kg" else "lb"
        MeasureDimension.LENGTH -> if (units == UnitSystem.METRIC) "cm" else "in"
        MeasureDimension.PERCENT -> "%"
    }

    /** The water series' unit, for the chart title: ml (labels may read 2.5 L) or fl oz. */
    fun waterUnitLabel(units: UnitSystem): String = if (units == UnitSystem.IMPERIAL) "fl oz" else "ml"

    /**
     * A chart value (already in display units) with its unit. Body fat and lengths 1 dp; water in
     * ml, or litres from 1,000 ml (2.5 L), or fl oz for imperial; counts as whole numbers.
     */
    fun chartValue(metric: ChartMetric, v: Double, units: UnitSystem): String = when (metric) {
        ChartMetric.WEIGHT -> "${number(v)} ${unitLabel(MeasureDimension.MASS, units)}"
        ChartMetric.BODY_FAT -> "${number(v)} %"
        ChartMetric.WAIST -> "${number(v)} ${unitLabel(MeasureDimension.LENGTH, units)}"
        ChartMetric.WATER -> when {
            units == UnitSystem.IMPERIAL -> "${number(v)} fl oz"
            v >= 1000 -> "${number(v / 1000, 2)} L"
            else -> "${number(v, 0)} ml"
        }
        ChartMetric.ENERGY -> "${number(v, 0)} kcal"
        ChartMetric.ACTIVE_MINUTES -> "${number(v, 0)} min"
        ChartMetric.WORKOUTS -> number(v, 0)
    }

    fun measurement(kind: MeasurementKind, metric: Double, units: UnitSystem) =
        "${number(toDisplay(kind.dimension, metric, units))} ${unitLabel(kind, units)}"
}

/** Metric value in the user's units, for display and input only. */
internal fun toDisplay(dim: MeasureDimension, metric: Double, units: UnitSystem): Double = when {
    units == UnitSystem.METRIC || dim == MeasureDimension.PERCENT -> metric
    dim == MeasureDimension.MASS -> UnitConversion.kgToLb(metric)
    else -> UnitConversion.cmToInches(metric)
}

/** User input in their units back to canonical metric (the API stays metric). */
internal fun toMetric(dim: MeasureDimension, display: Double, units: UnitSystem): Double = when {
    units == UnitSystem.METRIC || dim == MeasureDimension.PERCENT -> display
    dim == MeasureDimension.MASS -> UnitConversion.lbToKg(display)
    else -> UnitConversion.inchesToCm(display)
}

/** Parses user input as a non-negative decimal; accepts "1,234.5". Null when blank or invalid. */
internal fun String.toDecimalOrNull(): Double? {
    val t = trim().replace(",", "")
    if (t.isEmpty()) return null
    return t.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}

/**
 * One idempotency key per user action: a retry of the same action (same [signature]) reuses
 * the key; a different action gets a fresh one. [done] forgets it after success.
 */
internal class RetryKeys(private val newKey: () -> String) {
    private val keys = mutableMapOf<String, Pair<Any, String>>()

    fun keyFor(slot: String, signature: Any): String {
        val existing = keys[slot]
        if (existing != null && existing.first == signature) return existing.second
        return newKey().also { keys[slot] = signature to it }
    }

    fun done(slot: String) {
        keys.remove(slot)
    }
}
