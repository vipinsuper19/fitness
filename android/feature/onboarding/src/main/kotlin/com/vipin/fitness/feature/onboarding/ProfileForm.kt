package com.vipin.fitness.feature.onboarding

import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.ActivityLevel
import com.vipin.fitness.core.data.DietPreference
import com.vipin.fitness.core.data.ExperienceLevel
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileChanges
import com.vipin.fitness.core.data.Sex
import java.math.BigDecimal
import java.math.RoundingMode

enum class ProfileField { NAME, BIRTH_YEAR, SEX, UNITS, HEIGHT, WEIGHT, ACTIVITY, EXPERIENCE, DIET, CALORIES, WATER }

enum class FieldError { BIRTH_YEAR, HEIGHT, WEIGHT, CALORIES, WATER }

/**
 * Editable profile as the user sees it: text inputs in their display units, canonical metric
 * only after [parse]. Shared by onboarding and profile edit.
 */
data class ProfileForm(
    val displayName: String = "",
    val birthYear: String = "",
    val sex: Sex? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    val heightCm: String = "",
    val heightFt: String = "",
    val heightIn: String = "",
    val weight: String = "",
    val activity: ActivityLevel? = null,
    val experience: ExperienceLevel? = null,
    val diet: DietPreference? = null,
    val calories: String = "",
    val water: String = "",
) {
    /** Same values shown in other units. Unparseable text is kept as-is. */
    fun withUnits(target: UnitSystem): ProfileForm {
        if (target == units) return this
        val cm = parseHeightCm()
        val kg = parseWeightKg()
        val base = copy(units = target)
        return base.withHeight(cm.valueOr(null), keepRaw = cm is Parsed.Bad)
            .withWeight(kg.valueOr(null), keepRaw = kg is Parsed.Bad, raw = weight)
    }

    private fun withHeight(cm: Double?, keepRaw: Boolean): ProfileForm = when {
        keepRaw -> this
        cm == null -> copy(heightCm = "", heightFt = "", heightIn = "")
        units == UnitSystem.METRIC -> copy(heightCm = fmt(cm, 1), heightFt = "", heightIn = "")
        else -> UnitConversion.cmToFeetInches(cm).let { copy(heightCm = "", heightFt = it.feet.toString(), heightIn = fmt(it.inches, 0)) }
    }

    private fun withWeight(kg: Double?, keepRaw: Boolean, raw: String): ProfileForm = when {
        keepRaw -> copy(weight = raw)
        kg == null -> copy(weight = "")
        else -> copy(weight = fmt(UnitConversion.displayWeight(kg, units)!!, 1))
    }

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T?) : Parsed<T>
        data object Bad : Parsed<Nothing>
    }

    private fun <T> Parsed<T>.valueOr(d: T?): T? = (this as? Parsed.Ok)?.value ?: d

    private fun num(s: String): Parsed<Double> {
        val t = s.trim().replace(',', '.')
        if (t.isEmpty()) return Parsed.Ok(null)
        return t.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { Parsed.Ok(it) } ?: Parsed.Bad
    }

    private fun int(s: String, range: IntRange): Parsed<Int> {
        val t = s.trim()
        if (t.isEmpty()) return Parsed.Ok(null)
        return t.toIntOrNull()?.takeIf { it in range }?.let { Parsed.Ok(it) } ?: Parsed.Bad
    }

    fun parseHeightCm(): Parsed<Double> {
        val cm: Double? = if (units == UnitSystem.METRIC) {
            when (val p = num(heightCm)) { Parsed.Bad -> return Parsed.Bad; is Parsed.Ok -> p.value }
        } else {
            val ft = num(heightFt); val inch = num(heightIn)
            if (ft is Parsed.Bad || inch is Parsed.Bad) return Parsed.Bad
            val f = (ft as Parsed.Ok).value; val i = (inch as Parsed.Ok).value
            if (f == null && i == null) null else UnitConversion.feetInchesToCm((f ?: 0.0).toInt(), i ?: 0.0)
        }
        return if (cm == null || cm in 50.0..272.0) Parsed.Ok(cm?.let { round(it, 1) }) else Parsed.Bad
    }

    fun parseWeightKg(): Parsed<Double> = when (val p = num(weight)) {
        Parsed.Bad -> Parsed.Bad
        is Parsed.Ok -> {
            val kg = UnitConversion.weightToKg(p.value, units)
            if (kg == null || kg in 20.0..400.0) Parsed.Ok(kg?.let { round(it, 2) }) else Parsed.Bad
        }
    }

    /** Validates [fields]; returns the errors, empty when valid. */
    fun errors(fields: Set<ProfileField>, currentYear: Int): Map<ProfileField, FieldError> = buildMap {
        if (ProfileField.BIRTH_YEAR in fields && int(birthYear, 1900..currentYear) is Parsed.Bad) put(ProfileField.BIRTH_YEAR, FieldError.BIRTH_YEAR)
        if (ProfileField.HEIGHT in fields && parseHeightCm() is Parsed.Bad) put(ProfileField.HEIGHT, FieldError.HEIGHT)
        if (ProfileField.WEIGHT in fields && parseWeightKg() is Parsed.Bad) put(ProfileField.WEIGHT, FieldError.WEIGHT)
        if (ProfileField.CALORIES in fields && int(calories, 800..6000) is Parsed.Bad) put(ProfileField.CALORIES, FieldError.CALORIES)
        if (ProfileField.WATER in fields && int(water, 250..10000) is Parsed.Bad) put(ProfileField.WATER, FieldError.WATER)
    }

    /**
     * The PUT body for [fields]: a field the user didn't touch (same as [initial]) is Unchanged,
     * an emptied one is Clear, anything else is Set. Call only when [errors] is empty.
     */
    fun changes(fields: Set<ProfileField>, initial: ProfileForm, currentYear: Int): ProfileChanges {
        val base = initial.withUnits(units) // compare like with like after a unit switch
        fun touched(f: ProfileField) = f in fields && when (f) {
            ProfileField.NAME -> displayName.trim() != base.displayName.trim()
            ProfileField.BIRTH_YEAR -> birthYear.trim() != base.birthYear.trim()
            ProfileField.SEX -> sex != base.sex
            ProfileField.UNITS -> units != initial.units
            ProfileField.HEIGHT -> Triple(heightCm.trim(), heightFt.trim(), heightIn.trim()) != Triple(base.heightCm, base.heightFt, base.heightIn)
            ProfileField.WEIGHT -> weight.trim() != base.weight
            ProfileField.ACTIVITY -> activity != base.activity
            ProfileField.EXPERIENCE -> experience != base.experience
            ProfileField.DIET -> diet != base.diet
            ProfileField.CALORIES -> calories.trim() != base.calories.trim()
            ProfileField.WATER -> water.trim() != base.water.trim()
        }
        fun <T : Any> pick(f: ProfileField, v: () -> T?): Field<T> = if (touched(f)) Field.of(v()) else Field.Unchanged
        return ProfileChanges(
            displayName = pick(ProfileField.NAME) { displayName.trim().take(60).ifEmpty { null } },
            birthYear = pick(ProfileField.BIRTH_YEAR) { (int(birthYear, 1900..currentYear) as Parsed.Ok).value },
            sex = pick(ProfileField.SEX) { sex },
            unitSystem = if (touched(ProfileField.UNITS)) units else null,
            heightCm = pick(ProfileField.HEIGHT) { (parseHeightCm() as Parsed.Ok).value },
            weightKg = pick(ProfileField.WEIGHT) { (parseWeightKg() as Parsed.Ok).value },
            activityLevel = pick(ProfileField.ACTIVITY) { activity },
            experienceLevel = pick(ProfileField.EXPERIENCE) { experience },
            dietPreference = pick(ProfileField.DIET) { diet },
            dailyCalorieTargetKcal = pick(ProfileField.CALORIES) { (int(calories, 800..6000) as Parsed.Ok).value },
            dailyWaterTargetMl = pick(ProfileField.WATER) { (int(water, 250..10000) as Parsed.Ok).value },
        )
    }

    companion object {
        fun from(p: Profile): ProfileForm = ProfileForm(
            displayName = p.displayName.orEmpty(),
            birthYear = p.birthYear?.toString().orEmpty(),
            sex = p.sex,
            units = p.unitSystem,
            activity = p.activityLevel,
            experience = p.experienceLevel,
            diet = p.dietPreference,
            calories = p.dailyCalorieTargetKcal?.toString().orEmpty(),
            water = p.dailyWaterTargetMl?.toString().orEmpty(),
        ).withHeight(p.heightCm, keepRaw = false).withWeight(p.weightKg, keepRaw = false, raw = "")

        private fun round(v: Double, places: Int) = BigDecimal(v).setScale(places, RoundingMode.HALF_UP).toDouble()

        /** Compact number text: "72", "72.5" (no trailing .0). */
        internal fun fmt(v: Double, places: Int): String =
            BigDecimal(v).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
