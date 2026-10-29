package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.MacroBasis
import com.vipin.fitness.core.data.MacroInput
import com.vipin.fitness.core.data.MacroSuggestion
import com.vipin.fitness.core.data.MacroSuggestionRepository
import com.vipin.fitness.core.data.SuggestedMacros
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred

/** [result] answers each call (default offline); [gate] holds a call open so tests can race it. */
class FakeMacroSuggestionRepository : MacroSuggestionRepository {
    var result: () -> ApiResult<MacroSuggestion> = { FAKE_NETWORK_FAILURE }
    var gate: CompletableDeferred<Unit>? = null
    var calls = 0

    override suspend fun suggestion(): ApiResult<MacroSuggestion> {
        calls++
        val r = result
        gate?.await()
        return r()
    }
}

/** Spec example A: 2000 kcal, 70 kg, lose_weight → 112 / 262 / 56 g, 2000 kcal. */
fun testMacroSuggestion(
    proteinG: Int = 112,
    carbsG: Int = 262,
    fatG: Int = 56,
    kcal: Int = 4 * proteinG + 4 * carbsG + 9 * fatG,
) = MacroSuggestion(
    suggestion = SuggestedMacros(proteinG, carbsG, fatG, kcal),
    missingInputs = emptyList(),
    basis = MacroBasis(2000, 70.0, "lose_weight", false, null, 1.6, 25),
    formulaVersion = 1,
)

fun testMissingMacroInputs(vararg missing: MacroInput = arrayOf(MacroInput.CALORIE_TARGET, MacroInput.WEIGHT)) = MacroSuggestion(
    suggestion = null,
    missingInputs = missing.toList(),
    basis = MacroBasis(null, null, "maintain", true, null, 1.2, 25),
    formulaVersion = 1,
)
