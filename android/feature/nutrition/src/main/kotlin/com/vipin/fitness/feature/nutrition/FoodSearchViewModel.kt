package com.vipin.fitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.Food
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.NewFood
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.NutritionRepository
import com.vipin.fitness.core.data.ServingUnit
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

/** Picking a food and its quantity before it goes into the diary. */
data class PortionForm(
    val food: Food,
    val servings: String = "1",
    val editMacros: Boolean = false,
    val macros: MacroForm = MacroForm.from(food.nutrients),
    /** True once a macro field was actually typed in; only then is an override sent. */
    val macrosEdited: Boolean = false,
    val errors: Set<String> = emptySet(),
) {
    /** Live preview: computed macros for the typed servings, or the hand-edited values. */
    fun preview(): Nutrients? =
        if (overriding) macros.parse() else parseServings(servings)?.let { food.nutrients.times(it) }

    val overriding: Boolean get() = editMacros && macrosEdited

    /** Grams for the typed servings when the food knows its serving weight. */
    fun grams(): Double? = food.gramsPerServing?.let { g -> parseServings(servings)?.let { it * g } }
}

/** New custom food. Nutrients are per one serving. */
data class CustomFoodForm(
    val name: String = "",
    val brand: String = "",
    val servingSize: String = "1",
    val servingUnit: ServingUnit = ServingUnit.G,
    val gramsPerServing: String = "",
    val macros: MacroForm = MacroForm(),
    val vegetarian: Boolean? = null,
    val errors: Set<String> = emptySet(),
) {
    fun toNewFood(): Pair<NewFood?, Set<String>> {
        val errors = mutableSetOf<String>()
        val n = name.trim().takeIf { it.length in 1..120 } ?: run { errors += F_NAME; null }
        if (brand.trim().length > 80) errors += F_BRAND
        val size = servingSize.toDecimalOrNull()?.takeIf { it > 0 && it <= 10_000 } ?: run { errors += F_SIZE; null }
        val grams = if (gramsPerServing.isBlank()) null else gramsPerServing.toDecimalOrNull()?.takeIf { it > 0 && it <= 10_000 }
        if (gramsPerServing.isNotBlank() && grams == null) errors += F_GRAMS
        val nutrients = macros.parse() ?: run { errors += F_MACROS; null }
        if (errors.isNotEmpty()) return null to errors
        val g = grams ?: when (servingUnit) {
            ServingUnit.G -> size // metric: a gram serving weighs what it says
            else -> null
        }
        return NewFood(n!!, brand.trim().ifBlank { null }, size!!, servingUnit, g, nutrients!!, vegetarian) to emptySet()
    }

    companion object {
        const val F_NAME = "name"
        const val F_BRAND = "brand"
        const val F_SIZE = "servingSize"
        const val F_GRAMS = "gramsPerServing"
        const val F_MACROS = "macros"
    }
}

data class FoodSearchState(
    val date: LocalDate,
    val meal: MealType = MealType.BREAKFAST,
    val query: String = "",
    val results: List<Food> = emptyList(),
    val nextCursor: String? = null,
    /** The query that produced [results] and [nextCursor]; Load more pages this, not the edited text. */
    val searchedQuery: String? = null,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val portion: PortionForm? = null,
    val custom: CustomFoodForm? = null,
    val saving: Boolean = false,
    val error: UiText? = null,
    /** Set once the entry is logged: the screen returns to the diary. */
    val added: Boolean = false,
)

/** Search the catalog and custom foods, create a custom food, choose servings and log it to a meal. */
@HiltViewModel
class FoodSearchViewModel @Inject constructor(
    private val repo: NutritionRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(FoodSearchState(date = clock.today()))
    val state: StateFlow<FoodSearchState> = _state.asStateFlow()

    internal var newKey: () -> String = ::newIdempotencyKey
    private val keys = RetryKeys { newKey() }
    private var searchJob: Job? = null

    /** Called when the screen opens for a given day and meal; resets a finished flow. */
    fun start(date: LocalDate, meal: MealType) = _state.update {
        if (it.date == date && it.meal == meal && !it.added) it
        else FoodSearchState(date = date, meal = meal, query = it.query, results = it.results, nextCursor = it.nextCursor, searchedQuery = it.searchedQuery, searched = it.searched)
    }

    fun onQuery(q: String) = _state.update {
        val query = q.take(100)
        // A changed query must not page with the old search's cursor.
        if (query.trim() == it.searchedQuery) it.copy(query = query) else it.copy(query = query, nextCursor = null)
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            when (val r = repo.searchFoods(q)) {
                is ApiResult.Success -> _state.update {
                    it.copy(searching = false, searched = true, results = r.value.items, nextCursor = r.value.nextCursor, searchedQuery = q)
                }
                is ApiResult.Failure -> _state.update { it.copy(searching = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        val query = s.searchedQuery ?: return
        if (s.searching) return
        _state.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            when (val r = repo.searchFoods(query, cursor)) {
                is ApiResult.Success -> _state.update {
                    if (it.searchedQuery != query) it else it.copy(searching = false, results = (it.results + r.value.items).distinctBy { f -> f.id }, nextCursor = r.value.nextCursor)
                }
                is ApiResult.Failure -> _state.update { it.copy(searching = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    // --- Portion ---

    fun select(food: Food) = _state.update { it.copy(portion = PortionForm(food), error = null) }
    fun clearSelection() = _state.update { it.copy(portion = null) }
    fun onMeal(meal: MealType) = _state.update { it.copy(meal = meal) }
    fun onPortion(change: (PortionForm) -> PortionForm) = _state.update { s -> s.copy(portion = s.portion?.let(change)?.copy(errors = emptySet())) }

    fun add() {
        val s = _state.value
        val p = s.portion ?: return
        if (s.saving) return
        val servings = parseServings(p.servings)
        val override = if (p.overriding) p.macros.parse() else null
        val errors = buildSet {
            if (servings == null) add(DiaryViewModel.FIELD_SERVINGS)
            if (p.overriding && override == null) add(DiaryViewModel.FIELD_MACROS)
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(portion = p.copy(errors = errors)) }
            return
        }
        val signature = listOf(p.food.id, s.date, s.meal, servings, override)
        val key = keys.keyFor(SLOT_LOG, signature)
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.logFood(p.food.id, s.date, s.meal, servings!!, override, key)) {
                is ApiResult.Success -> {
                    keys.done(SLOT_LOG)
                    _state.update { it.copy(saving = false, portion = null, added = true) }
                }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    // --- Custom food ---

    fun startCustom() = _state.update { it.copy(custom = CustomFoodForm(name = it.query.trim()), error = null) }
    fun cancelCustom() = _state.update { it.copy(custom = null) }
    fun onCustom(change: (CustomFoodForm) -> CustomFoodForm) = _state.update { s -> s.copy(custom = s.custom?.let(change)?.copy(errors = emptySet())) }

    /** Creates the food, then opens it in the portion step so it can be logged straight away. */
    fun saveCustom() {
        val s = _state.value
        val form = s.custom ?: return
        if (s.saving) return
        val (food, errors) = form.toNewFood()
        if (food == null) {
            _state.update { it.copy(custom = form.copy(errors = errors)) }
            return
        }
        val key = keys.keyFor(SLOT_FOOD, food)
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.createFood(food, key)) {
                is ApiResult.Success -> {
                    keys.done(SLOT_FOOD)
                    _state.update {
                        it.copy(saving = false, custom = null, portion = PortionForm(r.value), results = listOf(r.value) + it.results)
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, error = UiText.Raw(r.error.userMessage())) }
            }
        }
    }

    private companion object {
        const val SLOT_LOG = "log"
        const val SLOT_FOOD = "food"
    }
}
