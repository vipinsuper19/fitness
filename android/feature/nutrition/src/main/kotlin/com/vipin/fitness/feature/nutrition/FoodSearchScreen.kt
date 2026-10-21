package com.vipin.fitness.feature.nutrition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.Food
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.ServingUnit
import java.time.LocalDate

@Composable
internal fun FoodSearchScreen(date: LocalDate, meal: MealType, onDone: (added: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val vm: FoodSearchViewModel = hiltViewModel(key = "nutrition_food_search")
    LaunchedEffect(date, meal) { vm.start(date, meal) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.added) { if (s.added) onDone(true) }
    BackHandler {
        when {
            s.portion != null -> vm.clearSelection()
            s.custom != null -> vm.cancelCustom()
            else -> onDone(false)
        }
    }
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDone(false) }) { Text("‹ " + stringResource(R.string.nutrition_back)) }
            Text(stringResource(R.string.search_add_to, stringResource(s.meal.label())), style = MaterialTheme.typography.titleMedium)
        }
        if (s.saving || s.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.error?.let { Text(it.resolve(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        val portion = s.portion
        val custom = s.custom
        when {
            portion != null -> PortionPanel(portion, s, vm)
            custom != null -> CustomFoodPanel(custom, s.saving, vm)
            else -> SearchPanel(s, vm)
        }
    }
}

@Composable
private fun SearchPanel(s: FoodSearchState, vm: FoodSearchViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::onQuery,
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                modifier = Modifier.fillMaxWidth().testTag("food_query"),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::search, enabled = s.query.isNotBlank() && !s.searching) { Text(stringResource(R.string.search_action)) }
                OutlinedButton(onClick = vm::startCustom) { Text(stringResource(R.string.custom_food_create)) }
            }
        }
        if (s.searched && s.results.isEmpty() && !s.searching) item { Text(stringResource(R.string.search_no_results)) }
        items(s.results, key = { it.id }) { food -> FoodRow(food) { vm.select(food) } }
        if (s.nextCursor != null) item { TextButton(onClick = vm::loadMore, enabled = !s.searching) { Text(stringResource(R.string.search_load_more)) } }
    }
}

@Composable
private fun FoodRow(food: Food, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp).testTag("food_${food.id}")) {
        Row {
            Text(listOfNotNull(food.name, food.brand).joinToString(" · "), Modifier.weight(1f))
            if (food.custom) Text(stringResource(R.string.custom_badge), style = MaterialTheme.typography.labelSmall)
        }
        food.nameHi?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "${NutritionFormat.kcal(food.nutrients.energyKcal)} " +
                stringResource(R.string.per_serving, NutritionFormat.number(food.servingSize, 2), food.servingUnit.wire) +
                " · " + NutritionFormat.macros(food.nutrients),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PortionPanel(p: PortionForm, s: FoodSearchState, vm: FoodSearchViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(p.food.name, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.per_serving, NutritionFormat.number(p.food.servingSize, 2), p.food.servingUnit.wire), style = MaterialTheme.typography.bodySmall)
        MealPicker(s.meal, vm::onMeal, enabled = !s.saving)
        NumberField(
            p.servings, { v -> vm.onPortion { it.copy(servings = v) } }, stringResource(R.string.field_servings), Modifier.fillMaxWidth().testTag("servings"),
            error = if (DiaryViewModel.FIELD_SERVINGS in p.errors) stringResource(R.string.servings_invalid) else null,
        )
        p.grams()?.let { Text(stringResource(R.string.portion_grams, NutritionFormat.grams(it)), style = MaterialTheme.typography.bodySmall) }
        SwitchRow(stringResource(R.string.edit_macros), p.editMacros) { on ->
            vm.onPortion { f ->
                val base = parseServings(f.servings)?.let { f.food.nutrients.times(it) } ?: f.food.nutrients
                f.copy(editMacros = on, macros = if (on) MacroForm.from(base.rounded1dp()) else f.macros)
            }
        }
        if (p.editMacros) MacroFields(p.macros, { m -> vm.onPortion { it.copy(macros = m) } }, DiaryViewModel.FIELD_MACROS in p.errors)
        p.preview()?.let { n ->
            Text(stringResource(R.string.portion_total), style = MaterialTheme.typography.titleSmall)
            Text("${NutritionFormat.kcal(n.energyKcal)} · ${NutritionFormat.macros(n)}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::clearSelection, enabled = !s.saving) { Text(stringResource(R.string.nutrition_back)) }
            Button(onClick = vm::add, enabled = !s.saving, modifier = Modifier.testTag("add_portion")) {
                Text(stringResource(R.string.search_add_to, stringResource(s.meal.label())))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomFoodPanel(f: CustomFoodForm, saving: Boolean, vm: FoodSearchViewModel) {
    fun err(key: String, res: Int): Int? = if (key in f.errors) res else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.custom_food_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            f.name, { v -> vm.onCustom { it.copy(name = v.take(120)) } }, label = { Text(stringResource(R.string.field_name)) },
            singleLine = true, isError = CustomFoodForm.F_NAME in f.errors, modifier = Modifier.fillMaxWidth(),
            supportingText = err(CustomFoodForm.F_NAME, R.string.name_invalid)?.let { { Text(stringResource(it)) } },
        )
        OutlinedTextField(
            f.brand, { v -> vm.onCustom { it.copy(brand = v.take(80)) } }, label = { Text(stringResource(R.string.field_brand)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        NumberField(
            f.servingSize, { v -> vm.onCustom { it.copy(servingSize = v) } }, stringResource(R.string.field_serving_size), Modifier.fillMaxWidth(),
            error = err(CustomFoodForm.F_SIZE, R.string.size_invalid)?.let { stringResource(it) },
        )
        Text(stringResource(R.string.field_serving_unit), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServingUnit.entries.forEach { u ->
                FilterChip(selected = u == f.servingUnit, onClick = { vm.onCustom { it.copy(servingUnit = u) } }, label = { Text(u.wire) })
            }
        }
        if (f.servingUnit != ServingUnit.G) {
            NumberField(
                f.gramsPerServing, { v -> vm.onCustom { it.copy(gramsPerServing = v) } }, stringResource(R.string.field_grams_per_serving),
                Modifier.fillMaxWidth(), error = err(CustomFoodForm.F_GRAMS, R.string.grams_invalid)?.let { stringResource(it) },
            )
        }
        Text(stringResource(R.string.macros_per_serving), style = MaterialTheme.typography.titleSmall)
        MacroFields(f.macros, { m -> vm.onCustom { it.copy(macros = m) } }, CustomFoodForm.F_MACROS in f.errors)
        SwitchRow(stringResource(R.string.field_vegetarian), f.vegetarian == true) { on -> vm.onCustom { it.copy(vegetarian = on) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::cancelCustom, enabled = !saving) { Text(stringResource(R.string.nutrition_cancel)) }
            Button(onClick = vm::saveCustom, enabled = !saving) { Text(stringResource(R.string.nutrition_save)) }
        }
    }
}
