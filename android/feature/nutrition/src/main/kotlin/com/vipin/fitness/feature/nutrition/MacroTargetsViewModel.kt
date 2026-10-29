package com.vipin.fitness.feature.nutrition

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.UnitPreference
import com.vipin.fitness.core.data.MacroInput
import com.vipin.fitness.core.data.MacroSuggestion
import com.vipin.fitness.core.data.MacroSuggestionRepository
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileChanges
import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.SuggestedMacros
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MacroError { OFFLINE, FAILED }

internal fun ApiError.toMacroError() = if (this == ApiError.Network) MacroError.OFFLINE else MacroError.FAILED

/** The stored targets shown on the screen. Null = not set. */
data class MacroTargets(val proteinG: Int?, val carbsG: Int?, val fatG: Int?, val calorieTargetKcal: Int?) {
    companion object {
        fun from(p: Profile) = MacroTargets(p.dailyProteinTargetG, p.dailyCarbsTargetG, p.dailyFatTargetG, p.dailyCalorieTargetKcal)
    }
}

data class MacroTargetsState(
    val loading: Boolean = true,
    val loadError: MacroError? = null,
    val targets: MacroTargets? = null,
    val suggesting: Boolean = false,
    val suggestError: MacroError? = null,
    /** Shown for review until applied or dismissed. */
    val suggestion: MacroSuggestion? = null,
    val applying: Boolean = false,
    val applyError: MacroError? = null,
    val applied: Boolean = false,
    val units: UnitSystem = UnitSystem.METRIC,
)

/** Macro targets with a rule-based suggestion (GET /me/macro-suggestion) applied via PUT /me/profile. */
@HiltViewModel
class MacroTargetsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val suggestions: MacroSuggestionRepository,
    units: UnitPreference,
) : ViewModel() {
    private val _state = MutableStateFlow(MacroTargetsState())
    val state: StateFlow<MacroTargetsState> = _state.asStateFlow()

    internal var newKey: () -> String = ::newIdempotencyKey
    /** Bumped per request so a late response (an older load or suggestion) is dropped. */
    private var loadGen = 0
    private var suggestGen = 0
    /** One key per apply action: a retry of the same grams reuses it. */
    private var pending: Pair<SuggestedMacros, String>? = null

    init {
        viewModelScope.launch { units.units.collect { u -> _state.update { it.copy(units = u) } } }
        load()
    }

    fun load() {
        val gen = ++loadGen
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val r = profiles.get()
            if (gen != loadGen) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(loading = false, targets = MacroTargets.from(r.value))
                    is ApiResult.Failure -> it.copy(loading = false, loadError = r.error.toMacroError())
                }
            }
        }
    }

    fun suggest() {
        if (_state.value.applying) return
        val gen = ++suggestGen
        _state.update { it.copy(suggesting = true, suggestError = null, suggestion = null, applyError = null, applied = false) }
        viewModelScope.launch {
            val r = suggestions.suggestion()
            if (gen != suggestGen) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(suggesting = false, suggestion = r.value)
                    is ApiResult.Failure -> it.copy(suggesting = false, suggestError = r.error.toMacroError())
                }
            }
        }
    }

    fun dismiss() {
        suggestGen++
        _state.update { it.copy(suggesting = false, suggestError = null, suggestion = null, applyError = null) }
    }

    /** Saves the reviewed grams. kcal is derived, so the calorie target is left alone. 0 is sent as a clear (stored as null). */
    fun apply() {
        val s = _state.value
        val sug = s.suggestion?.suggestion ?: return
        if (s.applying) return
        val key = pending?.takeIf { it.first == sug }?.second ?: newKey()
        pending = sug to key
        val gen = suggestGen
        _state.update { it.copy(applying = true, applyError = null) }
        viewModelScope.launch {
            val r = profiles.update(sug.toChanges(), key)
            _state.update {
                when (r) {
                    is ApiResult.Success -> {
                        pending = null
                        loadGen++ // a load still in flight is older than this result
                        it.copy(
                            applying = false,
                            applied = true,
                            loading = false,
                            loadError = null,
                            targets = MacroTargets.from(r.value),
                            suggestion = if (gen == suggestGen) null else it.suggestion,
                        )
                    }
                    is ApiResult.Failure -> it.copy(applying = false, applyError = r.error.toMacroError())
                }
            }
        }
    }
}

internal fun SuggestedMacros.toChanges() = ProfileChanges(
    dailyProteinTargetG = grams(proteinG),
    dailyCarbsTargetG = grams(carbsG),
    dailyFatTargetG = grams(fatG),
)

private fun grams(g: Int): Field<Int> = if (g > 0) Field.Set(g) else Field.Clear

/** en-IN, HALF_UP whole numbers; labels from resources. */
internal object MacroFormat {
    fun grams(res: Resources, g: Int?): String =
        g?.let { res.getString(R.string.macro_grams, NutritionFormat.number(it.toDouble(), 0)) } ?: res.getString(R.string.macro_not_set)

    fun kcal(res: Resources, k: Int?): String =
        k?.let { res.getString(R.string.macro_kcal, NutritionFormat.number(it.toDouble(), 0)) } ?: res.getString(R.string.macro_not_set)

    fun weight(res: Resources, kg: Double, units: UnitSystem): String = when (units) {
        UnitSystem.METRIC -> res.getString(R.string.macro_weight_kg, NutritionFormat.number(kg))
        UnitSystem.IMPERIAL -> res.getString(R.string.macro_weight_lb, NutritionFormat.number(UnitConversion.kgToLb(kg)))
    }

    fun goal(res: Resources, goal: String?, default: Boolean): String = when {
        default -> res.getString(R.string.macro_basis_default_goal)
        goal == "lose_weight" -> res.getString(R.string.macro_goal_lose_weight)
        goal == "gain_muscle" -> res.getString(R.string.macro_goal_gain_muscle)
        goal == "improve_endurance" -> res.getString(R.string.macro_goal_improve_endurance)
        else -> res.getString(R.string.macro_goal_maintain)
    }

    fun missingTitle(res: Resources, missing: List<MacroInput>): String =
        res.getQuantityString(R.plurals.macro_missing_title, missing.size, missing.size)

    fun missingItem(res: Resources, input: MacroInput): String = res.getString(R.string.macro_missing_item, missingLabel(res, input))

    fun missingLabel(res: Resources, input: MacroInput): String = res.getString(
        when (input) {
            MacroInput.CALORIE_TARGET -> R.string.macro_missing_calorie_target
            MacroInput.WEIGHT -> R.string.macro_missing_weight
            MacroInput.UNKNOWN -> R.string.macro_missing_unknown
        },
    )
}
