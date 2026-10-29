package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.UnitSystem

enum class Sex(val wire: String) { MALE("male"), FEMALE("female"), OTHER("other"), PREFER_NOT_TO_SAY("prefer_not_to_say") }
enum class ActivityLevel(val wire: String) { SEDENTARY("sedentary"), LIGHT("light"), MODERATE("moderate"), ACTIVE("active"), VERY_ACTIVE("very_active") }
enum class ExperienceLevel(val wire: String) { BEGINNER("beginner"), INTERMEDIATE("intermediate"), ADVANCED("advanced") }
enum class DietPreference(val wire: String) {
    VEGETARIAN("vegetarian"), EGGETARIAN("eggetarian"), NON_VEGETARIAN("non_vegetarian"), VEGAN("vegan"), JAIN("jain")
}

/** Domain profile. Body values are canonical metric; the UI converts for display. Null = not set. */
data class Profile(
    val displayName: String? = null,
    val birthYear: Int? = null,
    val sex: Sex? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val activityLevel: ActivityLevel? = null,
    val experienceLevel: ExperienceLevel? = null,
    val dietPreference: DietPreference? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val dailyCalorieTargetKcal: Int? = null,
    val dailyWaterTargetMl: Int? = null,
    /** Explicit macro targets in grams; null = not set (the server stores 0 as null). */
    val dailyProteinTargetG: Int? = null,
    val dailyCarbsTargetG: Int? = null,
    val dailyFatTargetG: Int? = null,
    val onboardingCompleted: Boolean = false,
    /** IANA zone id from the profile; null when unknown. Read-only here (not part of ProfileChanges). */
    val timezone: String? = null,
)

/** A partial PUT /me/profile. Defaults leave everything unchanged. */
data class ProfileChanges(
    val displayName: Field<String> = Field.Unchanged,
    val birthYear: Field<Int> = Field.Unchanged,
    val sex: Field<Sex> = Field.Unchanged,
    val heightCm: Field<Double> = Field.Unchanged,
    val weightKg: Field<Double> = Field.Unchanged,
    val activityLevel: Field<ActivityLevel> = Field.Unchanged,
    val experienceLevel: Field<ExperienceLevel> = Field.Unchanged,
    val dietPreference: Field<DietPreference> = Field.Unchanged,
    /** Not nullable in the contract, so only Unchanged or Set. */
    val unitSystem: UnitSystem? = null,
    val dailyCalorieTargetKcal: Field<Int> = Field.Unchanged,
    val dailyWaterTargetMl: Field<Int> = Field.Unchanged,
    val dailyProteinTargetG: Field<Int> = Field.Unchanged,
    val dailyCarbsTargetG: Field<Int> = Field.Unchanged,
    val dailyFatTargetG: Field<Int> = Field.Unchanged,
    val onboardingCompleted: Boolean? = null,
)
