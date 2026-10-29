package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `Profile` (GET/PUT /me/profile). Everything but the bookkeeping fields is nullable = "not set". */
@Serializable
data class ProfileDto(
    val userId: String,
    val displayName: String? = null,
    val birthYear: Int? = null,
    val sex: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val activityLevel: String? = null,
    val experienceLevel: String? = null,
    val dietPreference: String? = null,
    val unitSystem: String = "metric",
    val timezone: String = "Asia/Kolkata",
    val locale: String = "en-IN",
    val currency: String = "INR",
    val dailyCalorieTargetKcal: Int? = null,
    val dailyWaterTargetMl: Int? = null,
    /** Macro targets (#85): integer grams 0–1000; reads never return 0 (normalized to null). */
    val dailyProteinTargetG: Int? = null,
    val dailyCarbsTargetG: Int? = null,
    val dailyFatTargetG: Int? = null,
    val onboardingCompleted: Boolean = false,
    val updatedAt: String,
)
