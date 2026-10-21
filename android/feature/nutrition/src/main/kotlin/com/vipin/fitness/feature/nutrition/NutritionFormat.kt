package com.vipin.fitness.feature.nutrition

import com.vipin.fitness.core.data.Nutrients
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import com.vipin.fitness.core.common.time.APP_ZONE
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** India first: en-IN formatting, metric units, dates in Asia/Kolkata. */
internal object NutritionFormat {
    val LOCALE: Locale = Locale.forLanguageTag("en-IN")
    val ZONE: ZoneId = APP_ZONE
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", LOCALE)

    fun number(v: Double, maxDecimals: Int = 1): String =
        NumberFormat.getNumberInstance(LOCALE).apply {
            maximumFractionDigits = maxDecimals
            minimumFractionDigits = 0
            roundingMode = RoundingMode.HALF_UP
        }.format(BigDecimal.valueOf(v)) // decimal value, so 1.45 rounds to 1.5 rather than the double 1.4499…

    fun kcal(v: Double) = "${number(v, 0)} kcal"
    fun grams(v: Double) = "${number(v)} g"
    fun ml(v: Int) = "${number(v.toDouble(), 0)} ml"
    fun date(d: LocalDate): String = d.format(dateFmt)
    fun macros(n: Nutrients) = "P ${grams(n.proteinG)} · C ${grams(n.carbsG)} · F ${grams(n.fatG)}"
}

/** Parses user input as a non-negative decimal; accepts "1,234.5" (en-IN grouping). Null when blank or invalid. */
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
