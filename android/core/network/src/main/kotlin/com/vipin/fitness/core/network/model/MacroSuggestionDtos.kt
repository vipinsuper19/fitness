package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `MacroSuggestion` (GET /me/macro-suggestion, #99–#101). Always 200; `suggestion` null when inputs are missing. */
@Serializable
data class MacroSuggestionDto(
    val suggestion: SuggestedMacrosDto? = null,
    val missingInputs: List<String> = emptyList(),
    val basis: MacroBasisDto? = null,
    val formulaVersion: Int = 1,
)

@Serializable
data class SuggestedMacrosDto(val proteinG: Int, val carbsG: Int, val fatG: Int, val kcal: Int)

@Serializable
data class MacroBasisDto(
    val calorieTargetKcal: Int? = null,
    val weightKg: Double? = null,
    val goal: String? = null,
    val goalSource: String? = null,
    val dietPreference: String? = null,
    val proteinGPerKg: Double? = null,
    val fatPct: Int? = null,
)
