package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `NutritionTargets`; a target the user hasn't set is null. */
@Serializable
data class NutritionTargetsDto(
    val kcal: Int? = null,
    val proteinG: Int? = null,
    val carbsG: Int? = null,
    val fatG: Int? = null,
    val waterMl: Int? = null,
)

/** Contract `NutritionAdherence`: one-decimal percents; each null independently. */
@Serializable
data class NutritionAdherenceDto(
    val kcalPct: Double? = null,
    val proteinPct: Double? = null,
    val carbsPct: Double? = null,
    val fatPct: Double? = null,
    val waterPct: Double? = null,
)

@Serializable
data class NutritionSummaryPointDto(
    val period: String,
    val daysInPeriod: Int,
    val daysLogged: Int,
    val totals: NutrientsDto? = null,
    val avgPerLoggedDay: NutrientsDto? = null,
    val waterMl: Int? = null,
    val waterDaysLogged: Int = 0,
    val avgWaterMlPerLoggedDay: Int? = null,
    val adherence: NutritionAdherenceDto? = null,
    val entryCount: Int = 0,
    /** #104: targets in effect on the period's last date; absent from older servers. */
    val targets: NutritionTargetsDto? = null,
    /** #104: the target changed within the period; absent (null) from older servers. */
    val targetsChanged: Boolean? = null,
)

@Serializable
data class NutritionSummaryDto(
    val from: String,
    val to: String,
    val bucket: String,
    val timezone: String,
    val targets: NutritionTargetsDto? = null,
    val points: List<NutritionSummaryPointDto>,
)
