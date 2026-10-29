package com.vipin.fitness.feature.workout

import androidx.annotation.StringRes
import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.common.format.IndianNumberFormat
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.progression.Prescription
import com.vipin.fitness.core.data.progression.ProgressionAction
import com.vipin.fitness.core.data.progression.ProgressionInputs
import com.vipin.fitness.core.data.progression.ProgressionReason
import com.vipin.fitness.core.data.workout.TrackingType
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure formatting for the Suggestions section: en-IN numbers, the user's weight unit, IST dates. */
internal object ProgressionFormat {
    private val EN_IN: Locale = Locale.forLanguageTag("en-IN")
    private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", EN_IN).withZone(APP_ZONE)

    fun weight(kg: Double, units: UnitSystem): String =
        if (units == UnitSystem.IMPERIAL) "${IndianNumberFormat.number(UnitConversion.kgToLb(kg), 1)} lb"
        else "${IndianNumberFormat.number(kg, 1)} kg"

    fun reps(min: Int?, max: Int?): String? = when {
        min == null && max == null -> null
        min == null || max == null || min == max -> IndianNumberFormat.integer((min ?: max!!).toLong())
        else -> "${IndianNumberFormat.integer(min.toLong())}\u2013${IndianNumberFormat.integer(max.toLong())}"
    }

    fun duration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0 -> "$s s"
            s == 0 -> "${IndianNumberFormat.integer(m.toLong())} min"
            else -> "${IndianNumberFormat.integer(m.toLong())} min $s s"
        }
    }

    /** "3 × 8–12 · 40 kg", "3 × 45 s". The tracking type decides reps vs duration when both could show. */
    fun prescription(p: Prescription, type: TrackingType?, units: UnitSystem): String {
        val sets = IndianNumberFormat.integer(p.sets.toLong())
        val reps = reps(p.repsMin, p.repsMax)
        val dur = p.durationSeconds?.let(::duration)
        val work = if (type?.usesDuration == true) dur ?: reps else reps ?: dur
        val head = if (work != null) "$sets \u00d7 $work" else sets
        return p.targetWeightKg?.let { "$head \u00b7 ${weight(it, units)}" } ?: head
    }

    fun dateTime(at: Instant): String = DATE_TIME.format(at)

    /** One piece of the "why" line: a string resource and its already formatted arguments. */
    data class Part(@StringRes val res: Int, val args: List<Any> = emptyList())

    /**
     * The short "why" line from `inputs` (never from reasonText). Empty for servers without inputs.
     * Session counts, hardest effort, the step rule (percent, cap, minimum step) and an active cooldown.
     */
    fun whyParts(i: ProgressionInputs?): List<Part> {
        if (i == null) return emptyList()
        val parts = mutableListOf<Part>()
        val considered = i.sessionsConsidered
        val met = i.sessionsMetTarget
        if (considered != null && met != null) {
            parts += Part(R.string.progression_why_sessions, listOf(met, considered, i.sessionsRequired ?: considered))
        }
        i.maxPerceivedDifficulty?.let { parts += Part(R.string.progression_why_effort, listOf(it)) }
        i.step?.let { st ->
            val pct = st.percent?.let { IndianNumberFormat.number(kotlin.math.abs(it), 1) }
            when {
                st.rule == "deload" && pct != null -> parts += Part(R.string.progression_why_step_down, listOf(pct))
                st.rule == "percent_increase" && pct != null -> parts += Part(R.string.progression_why_step_up, listOf(pct))
                st.rule == "rep_step" -> parts += Part(R.string.progression_why_rep_step)
                st.rule == "heaviest_used" -> parts += Part(R.string.progression_why_heaviest_used)
            }
            if (st.capApplied) st.capPercent?.let { parts += Part(R.string.progression_why_capped, listOf(IndianNumberFormat.number(it, 1))) }
            if (st.minStepApplied) parts += Part(R.string.progression_why_min_step)
        }
        if (i.cooldownActive) i.cooldownUntil?.let { parts += Part(R.string.progression_why_cooldown, listOf(dateTime(it))) }
        return parts
    }

    @StringRes
    fun reasonRes(reason: ProgressionReason, hasVariations: Boolean): Int = when (reason) {
        ProgressionReason.ALL_SETS_COMPLETED -> R.string.progression_reason_all_sets_completed
        ProgressionReason.NO_TARGET_SET -> R.string.progression_reason_no_target_set
        ProgressionReason.NOT_ENOUGH_HISTORY -> R.string.progression_reason_not_enough_history
        ProgressionReason.MISSED_TARGET -> R.string.progression_reason_missed_target
        ProgressionReason.HIGH_EFFORT -> R.string.progression_reason_high_effort
        ProgressionReason.COOLDOWN -> R.string.progression_reason_cooldown
        ProgressionReason.CAP_REACHED -> R.string.progression_reason_cap_reached
        ProgressionReason.REP_CEILING ->
            if (hasVariations) R.string.progression_reason_rep_ceiling_variations else R.string.progression_reason_rep_ceiling
        ProgressionReason.REPEATED_MISSES -> R.string.progression_reason_repeated_misses
        ProgressionReason.VERY_HARD -> R.string.progression_reason_very_hard
        ProgressionReason.UNKNOWN -> R.string.progression_reason_unknown
    }

    @StringRes
    fun actionRes(action: ProgressionAction?): Int = when (action) {
        ProgressionAction.INCREASE -> R.string.progression_action_increase
        ProgressionAction.HOLD -> R.string.progression_action_hold
        ProgressionAction.DELOAD -> R.string.progression_action_deload
        null -> R.string.progression_action_unknown
    }
}
