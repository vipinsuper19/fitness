package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.MacroSuggestionApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.MacroSuggestionDto
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A missing profile input. [UNKNOWN] covers values a newer server adds ("complete your profile"). */
enum class MacroInput(val wire: String) { CALORIE_TARGET("CALORIE_TARGET"), WEIGHT("WEIGHT"), UNKNOWN("") }

/** kcal is the sum of the rounded grams (4P + 4C + 9F), not the calorie target. */
data class SuggestedMacros(val proteinG: Int, val carbsG: Int, val fatG: Int, val kcal: Int)

data class MacroBasis(
    val calorieTargetKcal: Int?,
    val weightKg: Double?,
    val goal: String?,
    val defaultGoal: Boolean,
    val dietPreference: DietPreference?,
    val proteinGPerKg: Double?,
    val fatPct: Int?,
)

data class MacroSuggestion(
    val suggestion: SuggestedMacros?,
    /** In server order (CALORIE_TARGET, WEIGHT); unknown values collapse to one [MacroInput.UNKNOWN]. */
    val missingInputs: List<MacroInput>,
    val basis: MacroBasis?,
    val formulaVersion: Int,
)

fun MacroSuggestionDto.toDomain(): MacroSuggestion {
    val missing = missingInputs.map { w -> MacroInput.entries.firstOrNull { it != MacroInput.UNKNOWN && it.wire == w } ?: MacroInput.UNKNOWN }.distinct()
    return MacroSuggestion(
        // The contract pairs these: never show a suggestion while inputs are missing.
        suggestion = suggestion?.takeIf { missing.isEmpty() }?.let { SuggestedMacros(it.proteinG, it.carbsG, it.fatG, it.kcal) },
        missingInputs = if (suggestion == null && missing.isEmpty()) listOf(MacroInput.UNKNOWN) else missing,
        basis = basis?.let { b ->
            MacroBasis(
                calorieTargetKcal = b.calorieTargetKcal,
                weightKg = b.weightKg,
                goal = b.goal,
                defaultGoal = b.goalSource == "default",
                dietPreference = DietPreference.entries.firstOrNull { it.wire == b.dietPreference },
                proteinGPerKg = b.proteinGPerKg,
                fatPct = b.fatPct,
            )
        },
        formulaVersion = formulaVersion,
    )
}

interface MacroSuggestionRepository {
    suspend fun suggestion(): ApiResult<MacroSuggestion>
}

/** No cache, so nothing outlives a user: each screen asks again. */
@Singleton
class DefaultMacroSuggestionRepository @Inject constructor(
    private val api: MacroSuggestionApi,
    private val json: Json,
) : MacroSuggestionRepository {
    override suspend fun suggestion() = apiCall(json) { api.get().toDomain() }
}
