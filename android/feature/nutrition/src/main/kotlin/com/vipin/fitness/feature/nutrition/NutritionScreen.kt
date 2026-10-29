package com.vipin.fitness.feature.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.DailyNutrition
import com.vipin.fitness.core.data.FoodLog
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.WaterLog

/**
 * Nutrition tab: the daily diary, and food search on top of it when adding to a meal.
 * Kept inside the module so the app's nav files only reference [NutritionScreen].
 */
@Composable
fun NutritionScreen(modifier: Modifier = Modifier, onOpenProfile: () -> Unit = {}) {
    val diary: DiaryViewModel = hiltViewModel(key = "nutrition_diary")
    var addingTo by rememberSaveable { mutableStateOf<MealType?>(null) }
    var showSummary by rememberSaveable { mutableStateOf(false) }
    var showMacros by rememberSaveable { mutableStateOf(false) }
    val meal = addingTo
    if (showMacros) {
        MacroTargetsRoute(onBack = { showMacros = false }, onOpenProfile = onOpenProfile, modifier = modifier)
    } else if (showSummary) {
        NutritionSummaryRoute(onBack = { showSummary = false }, modifier = modifier)
    } else if (meal == null) {
        DiaryScreen(diary, onAdd = { addingTo = it }, onOpenSummary = { showSummary = true }, onOpenMacros = { showMacros = true }, modifier = modifier)
    } else {
        val date = diary.state.collectAsStateWithLifecycle().value.date
        FoodSearchScreen(
            date = date,
            meal = meal,
            onDone = { added ->
                addingTo = null
                if (added) diary.load()
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun DiaryScreen(vm: DiaryViewModel, onAdd: (MealType) -> Unit, onOpenSummary: () -> Unit, onOpenMacros: () -> Unit, modifier: Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    var editingWater by remember { mutableStateOf<WaterLog?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    Column(modifier.fillMaxSize()) {
        DateBar(s, vm)
        androidx.compose.material3.TextButton(onClick = onOpenSummary, modifier = Modifier.padding(horizontal = 8.dp).testTag("open_nutrition_summary")) {
            Text(stringResource(R.string.summary_open))
        }
        TextButton(onClick = onOpenMacros, modifier = Modifier.padding(horizontal = 8.dp).testTag(MacroTargetsTags.OPEN)) {
            Text(stringResource(R.string.macro_open))
        }
        when {
            s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.loadError != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.loadError!!.resolve(), color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::load) { Text(stringResource(R.string.nutrition_retry)) }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("diary_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                s.error?.let { e -> item { Text(e.resolve(), color = MaterialTheme.colorScheme.error) } }
                s.day?.let { day ->
                    item { TotalsCard(day.daily) }
                    MealType.entries.forEach { meal ->
                        item(key = meal.name) {
                            MealCard(meal, s.logsFor(meal), day.daily.byMeal[meal]?.energyKcal ?: 0.0, s.saving, { onAdd(meal) }, vm::startEdit)
                        }
                    }
                    item { WaterCard(day.daily, day.waterLogs, s.saving, vm::addWater) { editingWater = it } }
                }
            }
        }
    }
    s.editing?.let { EditEntryDialog(it, s.saving, vm) }
    editingWater?.let { w ->
        WaterDialog(
            log = w,
            saving = s.saving,
            onSave = { ml -> vm.updateWater(w.id, ml); editingWater = null },
            onDelete = { vm.deleteWater(w.id); editingWater = null },
            onDismiss = { editingWater = null },
        )
    }
}

@Composable
private fun DateBar(s: DiaryState, vm: DiaryViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = vm::previousDay) { Text("‹ " + stringResource(R.string.nutrition_prev_day)) }
        Text(
            if (s.date == s.today) stringResource(R.string.nutrition_today) else NutritionFormat.date(s.date),
            Modifier.weight(1f).clickable(onClick = vm::goToToday),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = vm::nextDay, enabled = s.canGoForward) { Text(stringResource(R.string.nutrition_next_day) + " ›") }
    }
}

@Composable
private fun TotalsCard(d: DailyNutrition) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.nutrition_daily_totals), style = MaterialTheme.typography.titleMedium)
            val kcal = NutritionFormat.kcal(d.totals.energyKcal)
            val target = d.calorieTargetKcal
            Text(
                if (target != null) stringResource(R.string.nutrition_kcal_of_target, kcal, NutritionFormat.kcal(target.toDouble())) else kcal,
                style = MaterialTheme.typography.headlineSmall,
            )
            if (target != null && target > 0) {
                LinearProgressIndicator(progress = { (d.totals.energyKcal / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(NutritionFormat.macros(d.totals), style = MaterialTheme.typography.bodyMedium)
            if (d.partialOptionalTotals) Text(stringResource(R.string.nutrition_partial_totals), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MealCard(meal: MealType, logs: List<FoodLog>, kcal: Double, saving: Boolean, onAdd: () -> Unit, onEdit: (FoodLog) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(meal.label()), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(NutritionFormat.kcal(kcal), style = MaterialTheme.typography.bodyMedium)
            }
            if (logs.isEmpty()) Text(stringResource(R.string.nutrition_no_entries), style = MaterialTheme.typography.bodySmall)
            logs.forEach { log ->
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().clickable(enabled = !saving) { onEdit(log) }.padding(vertical = 6.dp).testTag("log_${log.id}")) {
                    Row {
                        Text(log.foodName, Modifier.weight(1f))
                        Text(NutritionFormat.kcal(log.nutrients.energyKcal))
                    }
                    val servings = stringResource(R.string.nutrition_servings_value, NutritionFormat.number(log.servings, 2), stringResource(R.string.field_servings))
                    val edited = if (log.nutrientsEdited) " · " + stringResource(R.string.nutrition_edited) else ""
                    Text("$servings · ${NutritionFormat.macros(log.nutrients)}$edited", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onAdd, enabled = !saving, modifier = Modifier.testTag("add_${meal.name}")) { Text(stringResource(R.string.nutrition_add_food)) }
        }
    }
}

@Composable
private fun WaterCard(d: DailyNutrition, logs: List<WaterLog>, saving: Boolean, onAdd: (Int) -> Unit, onEdit: (WaterLog) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.water_title), style = MaterialTheme.typography.titleMedium)
            val target = d.waterTargetMl
            Text(if (target != null) stringResource(R.string.water_of_target, NutritionFormat.ml(d.waterMl), NutritionFormat.ml(target)) else NutritionFormat.ml(d.waterMl))
            if (target != null && target > 0) {
                LinearProgressIndicator(progress = { (d.waterMl.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(150, 250, 500).forEach { ml ->
                    OutlinedButton(onClick = { onAdd(ml) }, enabled = !saving, modifier = Modifier.testTag("water_add_$ml")) {
                        Text(stringResource(R.string.water_add_amount, NutritionFormat.ml(ml)))
                    }
                }
            }
            logs.forEach { w ->
                Text(NutritionFormat.ml(w.amountMl), Modifier.fillMaxWidth().clickable(enabled = !saving) { onEdit(w) }.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun EditEntryDialog(form: FoodLogForm, saving: Boolean, vm: DiaryViewModel) {
    AlertDialog(
        onDismissRequest = vm::cancelEdit,
        title = { Text(form.log.foodName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MealPicker(form.meal, { m -> vm.onEdit { it.copy(meal = m) } }, enabled = !saving)
                NumberField(
                    form.servings, { v -> vm.onEdit { it.copy(servings = v) } }, stringResource(R.string.field_servings), Modifier.fillMaxWidth(),
                    error = if (DiaryViewModel.FIELD_SERVINGS in form.errors) stringResource(R.string.servings_invalid) else null,
                )
                SwitchRow(stringResource(R.string.edit_macros), form.editMacros && !form.restoreComputed) { on ->
                    vm.onEdit { it.copy(editMacros = on, restoreComputed = !on && it.log.nutrientsEdited) }
                }
                if (form.editMacros && !form.restoreComputed) {
                    MacroFields(form.macros, { m -> vm.onEdit { it.copy(macros = m, macrosEdited = true) } }, DiaryViewModel.FIELD_MACROS in form.errors)
                }
                if (form.log.nutrientsEdited && form.restoreComputed) Text(stringResource(R.string.restore_computed), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.deleteLog(form.log.id) }, enabled = !saving) {
                    Text(stringResource(R.string.nutrition_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = vm::saveEdit, enabled = !saving) { Text(stringResource(R.string.nutrition_save)) } },
        dismissButton = { TextButton(onClick = vm::cancelEdit) { Text(stringResource(R.string.nutrition_cancel)) } },
    )
}

@Composable
private fun WaterDialog(log: WaterLog, saving: Boolean, onSave: (Int) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(log.id) { mutableStateOf(log.amountMl.toString()) }
    val ml = text.toIntOrNull()?.takeIf { it in 1..MAX_WATER_ML }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.water_edit)) },
        text = {
            Column {
                NumberField(
                    text, { text = it.replace(",", "") }, stringResource(R.string.water_amount_ml), Modifier.fillMaxWidth(),
                    error = if (ml == null) stringResource(R.string.water_invalid) else null, decimal = false,
                )
                TextButton(onClick = onDelete, enabled = !saving) { Text(stringResource(R.string.nutrition_delete), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = { ml?.let(onSave) }, enabled = ml != null) { Text(stringResource(R.string.nutrition_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.nutrition_cancel)) } },
    )
}

