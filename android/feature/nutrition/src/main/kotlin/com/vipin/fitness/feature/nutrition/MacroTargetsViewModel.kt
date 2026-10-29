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
import com.vipin.fitness.core.data.ProfileZone
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

enum class MacroError { OFFLINE, RATE_LIMITED, INVALID, FAILED }

internal fun ApiError.toMacroError() = when {
    this == ApiError.Network -> MacroError.OFFLINE
    this is ApiError.Http && status == 429 -> MacroError.RATE_LIMITED
    this is ApiError.Http && status == 400 -> MacroError.INVALID
    else -> MacroError.FAILED
}

enum class MacroField { PROTEIN, CARBS, FAT }

/** Why a typed gram value can't be saved. */
enum class GramsError { NOT_WHOLE, TOO_LARGE }

/** A parsed gram field: [value] null means clear (empty or 0). */
sealed interface GramsInput {
    data class Ok(val value: Int?) : GramsInput
    data class Invalid(val error: GramsError) : GramsInput
}

/** Whole grams 0–1000 (contract bounds); empty or 0 clears. Surrounding spaces are ignored. */
internal fun parseGrams(text: String): GramsInput {
    val t = text.trim()
    if (t.isEmpty()) return GramsInput.Ok(null)
    if (!t.all { it in '0'..'9' }) return GramsInput.Invalid(GramsError.NOT_WHOLE)
    val v = t.trimStart('0').ifEmpty { "0" }
    if (v.length > 4 || v.toInt() > MAX_GRAMS) return GramsInput.Invalid(GramsError.TOO_LARGE)
    return GramsInput.Ok(v.toInt().takeIf { it > 0 })
}

internal const val MAX_GRAMS = 1000

/** The manual editor: raw text per field, as typed. */
data class MacroEdit(val protein: String = "", val carbs: String = "", val fat: String = "") {
    fun text(f: MacroField) = when (f) {
        MacroField.PROTEIN -> protein
        MacroField.CARBS -> carbs
        MacroField.FAT -> fat
    }

    fun with(f: MacroField, v: String) = when (f) {
        MacroField.PROTEIN -> copy(protein = v)
        MacroField.CARBS -> copy(carbs = v)
        MacroField.FAT -> copy(fat = v)
    }

    fun errors(): Map<MacroField, GramsError> =
        MacroField.entries.mapNotNull { f -> (parseGrams(text(f)) as? GramsInput.Invalid)?.let { f to it.error } }.toMap()

    /** Only the macro fields whose value differs from [current]; null if invalid or nothing changed. */
    fun changes(current: MacroTargets): ProfileChanges? {
        if (errors().isNotEmpty()) return null
        fun field(f: MacroField, old: Int?): Field<Int> {
            val v = (parseGrams(text(f)) as GramsInput.Ok).value
            return when {
                v == old -> Field.Unchanged
                v == null -> Field.Clear
                else -> Field.Set(v)
            }
        }
        val c = ProfileChanges(
            dailyProteinTargetG = field(MacroField.PROTEIN, current.proteinG),
            dailyCarbsTargetG = field(MacroField.CARBS, current.carbsG),
            dailyFatTargetG = field(MacroField.FAT, current.fatG),
        )
        return c.takeIf { it != ProfileChanges() }
    }

    companion object {
        fun from(t: MacroTargets) = MacroEdit(t.proteinG?.toString().orEmpty(), t.carbsG?.toString().orEmpty(), t.fatG?.toString().orEmpty())
    }
}

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
    /** Manual editing; null when not editing. */
    val edit: MacroEdit? = null,
    val saving: Boolean = false,
    val saveError: MacroError? = null,
    /** With RATE_LIMITED: the Retry-After of the last failed save or apply, in ms. */
    val retryAfterMs: Long? = null,
    val saved: Boolean = false,
) {
    val editErrors: Map<MacroField, GramsError> get() = edit?.errors().orEmpty()
    val canSave: Boolean get() = !saving && targets != null && edit?.changes(targets) != null
}

/** Macro targets with a rule-based suggestion (GET /me/macro-suggestion) applied via PUT /me/profile. */
@HiltViewModel
class MacroTargetsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val suggestions: MacroSuggestionRepository,
    units: UnitPreference,
    /** Its token changes on sign-out/sign-in: a response for the previous user is dropped. */
    private val user: ProfileZone,
) : ViewModel() {
    private val _state = MutableStateFlow(MacroTargetsState())
    val state: StateFlow<MacroTargetsState> = _state.asStateFlow()

    internal var newKey: () -> String = ::newIdempotencyKey
    /** Bumped per request so a late response (an older load or suggestion) is dropped. */
    private var loadGen = 0
    private var suggestGen = 0
    /** One key per apply action: a retry of the same grams reuses it. */
    private var pending: Pair<SuggestedMacros, String>? = null
    private var saveGen = 0
    /** One key per manual save: a retry of the same changes reuses it. */
    private var pendingSave: Pair<ProfileChanges, String>? = null

    init {
        viewModelScope.launch { units.units.collect { u -> _state.update { it.copy(units = u) } } }
        load()
    }

    fun load() {
        val gen = ++loadGen
        val token = user.token()
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val r = profiles.get()
            if (gen != loadGen || token != user.token()) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(loading = false, targets = MacroTargets.from(r.value))
                    is ApiResult.Failure -> it.copy(loading = false, loadError = r.error.toMacroError())
                }
            }
        }
    }

    fun suggest() {
        if (_state.value.applying || _state.value.edit != null) return
        val gen = ++suggestGen
        val token = user.token()
        _state.update { it.copy(suggesting = true, suggestError = null, suggestion = null, applyError = null, applied = false) }
        viewModelScope.launch {
            val r = suggestions.suggestion()
            if (gen != suggestGen || token != user.token()) return@launch
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
        val token = user.token()
        _state.update { it.copy(applying = true, applyError = null, retryAfterMs = null) }
        viewModelScope.launch {
            val r = profiles.update(sug.toChanges(), key)
            if (token != user.token()) return@launch
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
                    is ApiResult.Failure -> it.copy(applying = false, applyError = r.error.toMacroError(), retryAfterMs = r.error.retryAfter())
                }
            }
        }
    }

    /** Opens the gram fields with the current targets; closes any suggestion (one flow at a time). */
    fun edit() {
        val s = _state.value
        val t = s.targets ?: return
        if (s.applying || s.edit != null) return
        dismiss()
        _state.update { it.copy(edit = MacroEdit.from(t), saveError = null, saved = false, applied = false) }
    }

    fun onField(field: MacroField, text: String) {
        if (_state.value.saving) return
        _state.update { s -> s.edit?.let { s.copy(edit = it.with(field, text)) } ?: s }
    }

    fun cancelEdit() {
        if (_state.value.saving) return
        saveGen++
        pendingSave = null
        _state.update { it.copy(edit = null, saveError = null, retryAfterMs = null) }
    }

    /** PUT /me/profile with only the changed macro fields; 0 or empty clears. */
    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val changes = s.edit!!.changes(s.targets!!) ?: return
        val key = pendingSave?.takeIf { it.first == changes }?.second ?: newKey()
        pendingSave = changes to key
        val gen = ++saveGen
        val token = user.token()
        _state.update { it.copy(saving = true, saveError = null, retryAfterMs = null) }
        viewModelScope.launch {
            val r = profiles.update(changes, key)
            if (gen != saveGen || token != user.token()) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> {
                        pendingSave = null
                        loadGen++ // an older load must not overwrite the saved targets
                        it.copy(saving = false, saved = true, edit = null, loading = false, loadError = null, targets = MacroTargets.from(r.value))
                    }
                    is ApiResult.Failure -> {
                        // A 400 rejected these values: a corrected save is a new action with a new key.
                        if (r.error.toMacroError() == MacroError.INVALID) pendingSave = null
                        it.copy(saving = false, saveError = r.error.toMacroError(), retryAfterMs = r.error.retryAfter())
                    }
                }
            }
        }
    }
}

private fun ApiError.retryAfter(): Long? = (this as? ApiError.Http)?.takeIf { it.status == 429 }?.retryAfterMs

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

    fun gramsError(res: Resources, e: GramsError): String = when (e) {
        GramsError.NOT_WHOLE -> res.getString(R.string.macro_edit_not_whole)
        GramsError.TOO_LARGE -> res.getString(R.string.macro_edit_too_large, NutritionFormat.number(MAX_GRAMS.toDouble(), 0))
    }

    private fun common(res: Resources, e: MacroError, retryAfterMs: Long?): String? = when (e) {
        MacroError.OFFLINE -> res.getString(R.string.macro_offline)
        MacroError.RATE_LIMITED -> retryAfterMs?.let { res.getString(R.string.macro_rate_limited, NutritionFormat.number(((it + 999) / 1000).toDouble(), 0)) }
            ?: res.getString(R.string.macro_rate_limited_unknown)
        else -> null
    }

    fun saveError(res: Resources, e: MacroError, retryAfterMs: Long?): String = common(res, e, retryAfterMs) ?: res.getString(
        if (e == MacroError.INVALID) R.string.macro_save_invalid else R.string.macro_save_failed,
    )

    fun applyError(res: Resources, e: MacroError, retryAfterMs: Long?): String = common(res, e, retryAfterMs) ?: res.getString(
        if (e == MacroError.INVALID) R.string.macro_save_invalid else R.string.macro_apply_failed,
    )

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
