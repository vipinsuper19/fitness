package com.vipin.fitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.DiaryDay
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.FoodLog
import com.vipin.fitness.core.data.FoodLogChanges
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.NutritionRepository
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.data.userMessage
import com.vipin.fitness.core.designsystem.UiText
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

// TODO: switch to com.vipin.fitness.core.common.time.today (AppTime.kt) once #31 merges.
private fun Clock.today(): LocalDate = LocalDate.now(this.withZone(NutritionFormat.ZONE))

data class DiaryState(
    val date: LocalDate,
    val today: LocalDate,
    val loading: Boolean = true,
    val loadError: UiText? = null,
    val day: DiaryDay? = null,
    val saving: Boolean = false,
    val error: UiText? = null,
    /** The entry open in the edit sheet, with its form. */
    val editing: FoodLogForm? = null,
) {
    val canGoForward get() = date < today
    fun logsFor(meal: MealType): List<FoodLog> = day?.foodLogs.orEmpty().filter { it.mealType == meal }
}

/** Edit form for a diary entry: meal, servings and (optionally) hand-edited macros. */
data class FoodLogForm(
    val log: FoodLog,
    val meal: MealType = log.mealType,
    val servings: String = NutritionFormat.number(log.servings, 2),
    val editMacros: Boolean = log.nutrientsEdited,
    val macros: MacroForm = MacroForm.from(log.nutrients),
    val restoreComputed: Boolean = false,
    /** True once a macro field was actually typed in; only then is an override sent. */
    val macrosEdited: Boolean = false,
    val errors: Set<String> = emptySet(),
)

/** Text fields for the four required macros. */
data class MacroForm(val kcal: String = "", val protein: String = "", val carbs: String = "", val fat: String = "") {
    fun parse(): Nutrients? {
        val k = kcal.toDecimalOrNull() ?: return null
        val p = protein.toDecimalOrNull() ?: return null
        val c = carbs.toDecimalOrNull() ?: return null
        val f = fat.toDecimalOrNull() ?: return null
        if (k > MAX_KCAL || p > MAX_MACRO_G || c > MAX_MACRO_G || f > MAX_MACRO_G) return null
        return Nutrients(k, p, c, f)
    }

    companion object {
        const val MAX_KCAL = 5000.0
        const val MAX_MACRO_G = 900.0
        fun from(n: Nutrients) = MacroForm(
            NutritionFormat.number(n.energyKcal).replace(",", ""),
            NutritionFormat.number(n.proteinG).replace(",", ""),
            NutritionFormat.number(n.carbsG).replace(",", ""),
            NutritionFormat.number(n.fatG).replace(",", ""),
        )
    }
}

internal const val MAX_SERVINGS = 100.0
internal const val MAX_WATER_ML = 5000

internal fun parseServings(s: String): Double? = s.toDecimalOrNull()?.takeIf { it > 0 && it <= MAX_SERVINGS }

/** The daily food diary: meals, totals against targets, water, edits and deletes. */
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val repo: NutritionRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(clock.today().let { DiaryState(date = it, today = it) })
    val state: StateFlow<DiaryState> = _state.asStateFlow()

    internal var newKey: () -> String = ::newIdempotencyKey
    private val keys = RetryKeys { newKey() }
    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        val date = _state.value.date
        loadJob?.cancel()
        _state.update { it.copy(loading = it.day?.daily?.date != date, loadError = null) }
        loadJob = viewModelScope.launch {
            when (val r = repo.day(date)) {
                is ApiResult.Success -> _state.update { if (it.date == date) it.copy(loading = false, day = r.value) else it }
                is ApiResult.Failure -> _state.update {
                    if (it.date == date) it.copy(loading = false, loadError = UiText.Raw(r.error.userMessage())) else it
                }
            }
        }
    }

    fun previousDay() = goTo(_state.value.date.minusDays(1))
    fun nextDay() {
        refreshToday()
        if (_state.value.canGoForward) goTo(_state.value.date.plusDays(1))
    }
    fun goToToday() {
        refreshToday()
        goTo(_state.value.today)
    }

    /** Called when the screen resumes: if the date rolled over and the user was on the old today, move them to the new one. */
    fun onResume() {
        val wasOnToday = _state.value.date == _state.value.today
        if (refreshToday() && wasOnToday) goTo(_state.value.today)
    }

    /** Updates [DiaryState.today] from the clock; true when it changed. */
    private fun refreshToday(): Boolean {
        val now = clock.today()
        if (now == _state.value.today) return false
        _state.update { it.copy(today = now) }
        return true
    }

    private fun goTo(date: LocalDate) {
        if (date == _state.value.date) return
        _state.update { it.copy(date = date, day = null, editing = null, error = null) }
        load()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // --- Water ---

    fun addWater(amountMl: Int) {
        if (amountMl !in 1..MAX_WATER_ML) return
        val date = _state.value.date
        write("water-add", date to amountMl) { key -> repo.addWater(date, amountMl, key) }
    }

    fun updateWater(id: String, amountMl: Int) {
        if (amountMl !in 1..MAX_WATER_ML) return
        val date = _state.value.date
        write("water-$id", amountMl) { key -> repo.updateWater(id, date, amountMl, key) }
    }

    fun deleteWater(id: String) = write("water-del-$id", id) { key -> repo.deleteWater(id, key) }

    // --- Food log edits ---

    fun startEdit(log: FoodLog) = _state.update { it.copy(editing = FoodLogForm(log), error = null) }
    fun cancelEdit() = _state.update { it.copy(editing = null) }
    fun onEdit(change: (FoodLogForm) -> FoodLogForm) = _state.update { s -> s.copy(editing = s.editing?.let(change)?.copy(errors = emptySet())) }

    fun saveEdit() {
        val form = _state.value.editing ?: return
        val servings = parseServings(form.servings)
        val overriding = form.editMacros && !form.restoreComputed && form.macrosEdited
        val macros = if (overriding) form.macros.parse() else null
        val errors = buildSet {
            if (servings == null) add(FIELD_SERVINGS)
            if (overriding && macros == null) add(FIELD_MACROS)
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(editing = form.copy(errors = errors)) }
            return
        }
        val log = form.log
        val nutrients: Field<Nutrients> = when {
            form.restoreComputed && log.nutrientsEdited -> Field.Clear
            macros != null && macros != log.nutrients.copy(fiberG = null, sugarG = null, sodiumMg = null) -> Field.Set(macros)
            else -> Field.Unchanged
        }
        val changes = FoodLogChanges(
            mealType = form.meal.takeIf { it != log.mealType },
            servings = servings.takeIf { it != log.servings },
            nutrients = nutrients,
        )
        if (changes == FoodLogChanges()) {
            cancelEdit()
            return
        }
        write("log-${log.id}", changes, closeEditor = true) { key -> repo.updateFoodLog(log.id, changes, key) }
    }

    fun deleteLog(id: String) = write("log-del-$id", id, closeEditor = true) { key -> repo.deleteFoodLog(id, key) }

    /** Runs one write with a retry-stable key, then reloads the day so totals come from the server. */
    private fun write(slot: String, signature: Any, closeEditor: Boolean = false, call: suspend (String) -> ApiResult<*>) {
        if (_state.value.saving) return
        val key = keys.keyFor(slot, signature)
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = call(key)) {
                is ApiResult.Success -> {
                    keys.done(slot)
                    _state.update { it.copy(saving = false, editing = if (closeEditor) null else it.editing) }
                    load()
                }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    companion object {
        const val FIELD_SERVINGS = "servings"
        const val FIELD_MACROS = "macros"
    }
}
