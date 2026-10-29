package com.vipin.fitness.feature.nutrition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.DietPreference
import com.vipin.fitness.core.data.MacroSuggestion

object MacroTargetsTags {
    const val OPEN = "open_macro_targets"
    const val SCREEN = "macro_targets"
    const val BACK = "macro_back"
    const val LOADING = "macro_loading"
    const val LOAD_ERROR = "macro_load_error"
    const val LOAD_RETRY = "macro_load_retry"
    const val PROTEIN = "macro_target_protein"
    const val CARBS = "macro_target_carbs"
    const val FAT = "macro_target_fat"
    const val SUGGEST = "macro_suggest"
    const val SUGGEST_LOADING = "macro_suggest_loading"
    const val SUGGEST_ERROR = "macro_suggest_error"
    const val SUGGEST_RETRY = "macro_suggest_retry"
    const val SUGGESTION = "macro_suggestion"
    const val SUGGESTED_PROTEIN = "macro_suggested_protein"
    const val SUGGESTED_CARBS = "macro_suggested_carbs"
    const val SUGGESTED_FAT = "macro_suggested_fat"
    const val SUGGESTED_KCAL = "macro_suggested_kcal"
    const val APPLY = "macro_apply"
    const val DISMISS = "macro_dismiss"
    const val APPLY_ERROR = "macro_apply_error"
    const val APPLIED = "macro_applied"
    const val MISSING = "macro_missing"
    fun missing(input: com.vipin.fitness.core.data.MacroInput) = "macro_missing_${input.name.lowercase()}"
    const val OPEN_PROFILE = "macro_open_profile"
    const val EDIT = "macro_edit"
    const val EDITOR = "macro_editor"
    const val EDIT_PROTEIN = "macro_edit_protein"
    const val EDIT_CARBS = "macro_edit_carbs"
    const val EDIT_FAT = "macro_edit_fat"
    fun editError(field: MacroField) = "macro_edit_error_${field.name.lowercase()}"
    const val EDIT_NOTE = "macro_edit_note"
    const val SAVE = "macro_save"
    const val CANCEL_EDIT = "macro_cancel_edit"
    const val SAVE_ERROR = "macro_save_error"
    const val SAVED = "macro_saved"
}

@Composable
fun MacroTargetsRoute(onBack: () -> Unit, onOpenProfile: () -> Unit, modifier: Modifier = Modifier) {
    val vm: MacroTargetsViewModel = hiltViewModel(key = "macro_targets")
    val s by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    MacroTargetsContent(
        s, onBack, onOpenProfile, vm::load, vm::suggest, vm::apply, vm::dismiss, modifier,
        MacroEditActions(vm::edit, vm::onField, vm::save, vm::cancelEdit),
    )
}

/** Manual target editing callbacks. */
data class MacroEditActions(
    val onEdit: () -> Unit = {},
    val onField: (MacroField, String) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

@Composable
fun MacroTargetsContent(
    s: MacroTargetsState,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onRetryLoad: () -> Unit,
    onSuggest: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    editActions: MacroEditActions = MacroEditActions(),
) {
    val res = LocalContext.current.resources
    Column(modifier.fillMaxSize().testTag(MacroTargetsTags.SCREEN)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(MacroTargetsTags.BACK)) { Text(stringResource(R.string.macro_back)) }
            Text(stringResource(R.string.macro_title), style = MaterialTheme.typography.titleLarge)
        }
        val t = s.targets
        when {
            s.loading -> Box(Modifier.fillMaxSize().testTag(MacroTargetsTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.loadError != null -> ErrorBlock(s.loadError.loadMessage(), MacroTargetsTags.LOAD_ERROR, MacroTargetsTags.LOAD_RETRY, onRetryLoad)
            t != null -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Line(stringResource(R.string.macro_protein), MacroFormat.grams(res, t.proteinG), MacroTargetsTags.PROTEIN)
                        Line(stringResource(R.string.macro_carbs), MacroFormat.grams(res, t.carbsG), MacroTargetsTags.CARBS)
                        Line(stringResource(R.string.macro_fat), MacroFormat.grams(res, t.fatG), MacroTargetsTags.FAT)
                        Line(stringResource(R.string.macro_calorie_target), MacroFormat.kcal(res, t.calorieTargetKcal), null)
                    }
                }
                if (s.saved) Text(stringResource(R.string.macro_saved), modifier = Modifier.testTag(MacroTargetsTags.SAVED))
                if (s.applied) Text(stringResource(R.string.macro_applied), modifier = Modifier.testTag(MacroTargetsTags.APPLIED))
                val sug = s.suggestion
                val edit = s.edit
                when {
                    edit != null -> EditCard(s, edit, editActions)
                    s.suggesting -> Box(Modifier.fillMaxWidth().testTag(MacroTargetsTags.SUGGEST_LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    s.suggestError != null -> ErrorBlock(s.suggestError.suggestMessage(), MacroTargetsTags.SUGGEST_ERROR, MacroTargetsTags.SUGGEST_RETRY, onSuggest)
                    sug != null && sug.suggestion != null -> SuggestionCard(s, sug, onApply, onDismiss)
                    sug != null -> MissingCard(sug, onOpenProfile)
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSuggest, modifier = Modifier.testTag(MacroTargetsTags.SUGGEST)) { Text(stringResource(R.string.macro_suggest)) }
                        OutlinedButton(onClick = editActions.onEdit, modifier = Modifier.testTag(MacroTargetsTags.EDIT)) { Text(stringResource(R.string.macro_edit)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(s: MacroTargetsState, m: MacroSuggestion, onApply: () -> Unit, onDismiss: () -> Unit) {
    val res = LocalContext.current.resources
    val g = m.suggestion ?: return
    Card(Modifier.fillMaxWidth().testTag(MacroTargetsTags.SUGGESTION)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.macro_suggestion_title), style = MaterialTheme.typography.titleMedium)
            Line(stringResource(R.string.macro_protein), MacroFormat.grams(res, g.proteinG), MacroTargetsTags.SUGGESTED_PROTEIN)
            Line(stringResource(R.string.macro_carbs), MacroFormat.grams(res, g.carbsG), MacroTargetsTags.SUGGESTED_CARBS)
            Line(stringResource(R.string.macro_fat), MacroFormat.grams(res, g.fatG), MacroTargetsTags.SUGGESTED_FAT)
            Line(stringResource(R.string.macro_kcal_total), MacroFormat.kcal(res, g.kcal), MacroTargetsTags.SUGGESTED_KCAL)
            m.basis?.let { b ->
                b.calorieTargetKcal?.let { k ->
                    Text(stringResource(R.string.macro_suggestion_note, MacroFormat.kcal(res, k)), style = MaterialTheme.typography.bodySmall)
                }
                b.weightKg?.let { w ->
                    Text(stringResource(R.string.macro_basis, MacroFormat.weight(res, w, s.units), MacroFormat.goal(res, b.goal, b.defaultGoal)), style = MaterialTheme.typography.bodySmall)
                }
                if (b.dietPreference in setOf(DietPreference.VEGETARIAN, DietPreference.VEGAN, DietPreference.JAIN, DietPreference.EGGETARIAN)) {
                    Text(stringResource(R.string.macro_diet_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            s.applyError?.let { Text(MacroFormat.applyError(res, it, s.retryAfterMs), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(MacroTargetsTags.APPLY_ERROR)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, enabled = !s.applying, modifier = Modifier.testTag(MacroTargetsTags.APPLY)) {
                    Text(stringResource(if (s.applyError != null) R.string.macro_retry else R.string.macro_apply))
                }
                OutlinedButton(onClick = onDismiss, enabled = !s.applying, modifier = Modifier.testTag(MacroTargetsTags.DISMISS)) { Text(stringResource(R.string.macro_dismiss)) }
            }
        }
    }
}

@Composable
private fun EditCard(s: MacroTargetsState, e: MacroEdit, a: MacroEditActions) {
    val res = LocalContext.current.resources
    val errors = s.editErrors
    Card(Modifier.fillMaxWidth().testTag(MacroTargetsTags.EDITOR)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.macro_edit_title), style = MaterialTheme.typography.titleMedium)
            MacroField.entries.forEach { f ->
                val err = errors[f]
                OutlinedTextField(
                    value = e.text(f),
                    onValueChange = { a.onField(f, it) },
                    enabled = !s.saving,
                    singleLine = true,
                    label = { Text(stringResource(f.label())) },
                    suffix = { Text(stringResource(R.string.macro_grams_unit)) },
                    isError = err != null,
                    supportingText = err?.let { er -> { Text(MacroFormat.gramsError(res, er), Modifier.testTag(MacroTargetsTags.editError(f))) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(f.editTag()),
                )
            }
            Text(stringResource(R.string.macro_edit_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.macro_edit_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(MacroTargetsTags.EDIT_NOTE))
            s.saveError?.let { Text(MacroFormat.saveError(res, it, s.retryAfterMs), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(MacroTargetsTags.SAVE_ERROR)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = a.onSave, enabled = s.canSave, modifier = Modifier.testTag(MacroTargetsTags.SAVE)) {
                    Text(stringResource(if (s.saveError != null && s.saveError != MacroError.INVALID) R.string.macro_retry else R.string.macro_save))
                }
                OutlinedButton(onClick = a.onCancel, enabled = !s.saving, modifier = Modifier.testTag(MacroTargetsTags.CANCEL_EDIT)) { Text(stringResource(R.string.macro_cancel)) }
            }
        }
    }
}

private fun MacroField.label() = when (this) {
    MacroField.PROTEIN -> R.string.macro_protein
    MacroField.CARBS -> R.string.macro_carbs
    MacroField.FAT -> R.string.macro_fat
}

private fun MacroField.editTag() = when (this) {
    MacroField.PROTEIN -> MacroTargetsTags.EDIT_PROTEIN
    MacroField.CARBS -> MacroTargetsTags.EDIT_CARBS
    MacroField.FAT -> MacroTargetsTags.EDIT_FAT
}

@Composable
private fun MissingCard(m: MacroSuggestion, onOpenProfile: () -> Unit) {
    val res = LocalContext.current.resources
    Card(Modifier.fillMaxWidth().testTag(MacroTargetsTags.MISSING)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(MacroFormat.missingTitle(res, m.missingInputs))
            m.missingInputs.forEach { Text(MacroFormat.missingItem(res, it), modifier = Modifier.testTag(MacroTargetsTags.missing(it))) }
            Button(onClick = onOpenProfile, modifier = Modifier.testTag(MacroTargetsTags.OPEN_PROFILE)) { Text(stringResource(R.string.macro_open_profile)) }
        }
    }
}

@Composable
private fun Line(label: String, value: String, tag: String?) {
    Row(Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it }, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorBlock(message: Int, tag: String, retryTag: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 8.dp).testTag(retryTag)) { Text(stringResource(R.string.macro_retry)) }
    }
}

private fun MacroError.loadMessage() = when (this) {
    MacroError.OFFLINE -> R.string.macro_offline
    MacroError.RATE_LIMITED -> R.string.macro_rate_limited_unknown
    else -> R.string.macro_failed
}
private fun MacroError.suggestMessage() = when (this) {
    MacroError.OFFLINE -> R.string.macro_offline
    MacroError.RATE_LIMITED -> R.string.macro_rate_limited_unknown
    else -> R.string.macro_suggest_failed
}
